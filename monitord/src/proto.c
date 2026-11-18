/* NDJSON wire format for monitord. Pure functions: no I/O, no allocation, no
   globals. monitord must never call setlocale(): the "%.*f" formatting below
   relies on the C locale printing '.' as the decimal separator. */

#include "proto.h"

#include <limits.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>

/* =========================================================================
   JSON writer: bounded, overflow-sticky. Invariant: len <= cap - 1, so there
   is always room for the final NUL.
   ========================================================================= */

typedef struct { char *buf; size_t cap; size_t len; bool ovf; } jw_t;

static void jw_init(jw_t *w, char *buf, size_t cap) {
    if (cap > INT_MAX) cap = INT_MAX;               /* lengths are returned as int */
    w->buf = buf; w->cap = cap; w->len = 0;
    w->ovf = (buf == NULL || cap == 0);
}

static void jw_put(jw_t *w, const char *s, size_t n) {
    if (w->ovf) return;
    if (n >= w->cap - w->len) { w->ovf = true; return; }    /* keep one byte for the NUL */
    memcpy(w->buf + w->len, s, n);
    w->len += n;
}

static void jw_puts(jw_t *w, const char *s) { jw_put(w, s, strlen(s)); }
static void jw_putc(jw_t *w, char c)        { jw_put(w, &c, 1); }

static void jw_fmt(jw_t *w, const char *fmt, ...) {
    char tmp[64];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= sizeof tmp) { w->ovf = true; return; }
    jw_put(w, tmp, (size_t)n);
}

static void jw_u64(jw_t *w, uint64_t v) { jw_fmt(w, "%llu", (unsigned long long)v); }
static void jw_i64(jw_t *w, int64_t v)  { jw_fmt(w, "%lld", (long long)v); }
static void jw_bool(jw_t *w, bool v)    { jw_puts(w, v ? "true" : "false"); }

/* Non-finite or absurd values become null: JSON has no NaN/Infinity, and a
   huge double would print hundreds of digits. */
static void jw_dbl(jw_t *w, double v, int decimals) {
    if (!isfinite(v) || fabs(v) > 1e15) { jw_puts(w, "null"); return; }
    jw_fmt(w, "%.*f", decimals, v);
}

/* Length of the valid UTF-8 sequence at p (1..4), or 0 if invalid. Follows the
   Unicode well-formedness table: no overlongs, no surrogates, nothing above U+10FFFF. */
static size_t utf8_seq_len(const unsigned char *p, size_t remaining) {
    unsigned char b = p[0], lo = 0x80, hi = 0xBF;   /* lo..hi: allowed range of the 2nd byte */
    size_t need;
    if (b < 0x80) return 1;
    if      (b >= 0xC2 && b <= 0xDF) need = 2;
    else if (b >= 0xE0 && b <= 0xEF) { need = 3; if (b == 0xE0) lo = 0xA0; if (b == 0xED) hi = 0x9F; }
    else if (b >= 0xF0 && b <= 0xF4) { need = 4; if (b == 0xF0) lo = 0x90; if (b == 0xF4) hi = 0x8F; }
    else return 0;
    if (remaining < need) return 0;
    if (p[1] < lo || p[1] > hi) return 0;
    for (size_t i = 2; i < need; i++)
        if ((p[i] & 0xC0) != 0x80) return 0;
    return need;
}

/* Writes a JSON string. Reads at most `max` bytes of s (stopping at NUL),
   escapes what JSON requires, and replaces malformed UTF-8 with U+FFFD. */
static void jw_str_n(jw_t *w, const char *s, size_t max) {
    if (!s) s = "";
    size_t n = strnlen(s, max);
    jw_putc(w, '"');
    for (size_t i = 0; i < n; ) {
        unsigned char b = (unsigned char)s[i];
        if (b == '"')       { jw_puts(w, "\\\""); i++; }
        else if (b == '\\') { jw_puts(w, "\\\\"); i++; }
        else if (b < 0x20)  { char e[8]; snprintf(e, sizeof e, "\\u%04x", (unsigned)b); jw_puts(w, e); i++; }
        else if (b < 0x80)  { jw_putc(w, (char)b); i++; }
        else {
            size_t k = utf8_seq_len((const unsigned char *)s + i, n - i);
            if (k) { jw_put(w, s + i, k); i += k; }
            else   { jw_put(w, "\xEF\xBF\xBD", 3); i++; }        /* U+FFFD */
        }
    }
    jw_putc(w, '"');
}

static void jw_str(jw_t *w, const char *s) { jw_str_n(w, s, 1024); }

static int jw_finish(jw_t *w) {
    jw_putc(w, '\n');
    if (w->ovf) return -1;
    w->buf[w->len] = '\0';
    return (int)w->len;
}

static void begin_msg(jw_t *w, const char *type) {
    jw_puts(w, "{\"v\":");
    jw_u64(w, PROTO_VERSION);
    jw_puts(w, ",\"type\":");
    jw_str(w, type);
}

/* =========================================================================
   Snapshot sections. An invalid section is null; an empty valid one is [].
   Counts are clamped so a corrupt struct can never read out of bounds.
   ========================================================================= */

static void write_cpu(jw_t *w, const cpu_stats_t *c) {
    if (!c->valid) { jw_puts(w, "null"); return; }
    unsigned n = c->core_count < MAX_CORES ? c->core_count : MAX_CORES;
    jw_puts(w, "{\"total_pct\":");
    jw_dbl(w, c->total_pct, 1);
    jw_puts(w, ",\"cores_pct\":[");
    for (unsigned i = 0; i < n; i++) {
        if (i) jw_putc(w, ',');
        jw_dbl(w, (double)c->cores_pct[i], 1);
    }
    jw_puts(w, "]}");
}

static void write_mem(jw_t *w, const mem_stats_t *m) {
    if (!m->valid) { jw_puts(w, "null"); return; }
    jw_puts(w, "{\"total_bytes\":");        jw_u64(w, m->total_bytes);
    jw_puts(w, ",\"avail_bytes\":");        jw_u64(w, m->avail_bytes);
    jw_puts(w, ",\"commit_bytes\":");       jw_u64(w, m->commit_bytes);
    jw_puts(w, ",\"commit_limit_bytes\":"); jw_u64(w, m->commit_limit_bytes);
    jw_putc(w, '}');
}

static void write_disks(jw_t *w, const snapshot_t *s) {
    if (!s->disks_valid) { jw_puts(w, "null"); return; }
    unsigned n = s->disk_count < MAX_DISKS ? s->disk_count : MAX_DISKS;
    jw_putc(w, '[');
    for (unsigned i = 0; i < n; i++) {
        const disk_stats_t *d = &s->disks[i];
        if (i) jw_putc(w, ',');
        jw_puts(w, "{\"name\":");        jw_str_n(w, d->name, sizeof d->name);
        jw_puts(w, ",\"total_bytes\":"); jw_u64(w, d->total_bytes);
        jw_puts(w, ",\"free_bytes\":");  jw_u64(w, d->free_bytes);
        jw_puts(w, ",\"read_bps\":");    jw_u64(w, d->read_bps);
        jw_puts(w, ",\"write_bps\":");   jw_u64(w, d->write_bps);
        jw_puts(w, ",\"queue\":");       jw_dbl(w, d->queue, 2);
        jw_putc(w, '}');
    }
    jw_putc(w, ']');
}

static void write_procs(jw_t *w, const snapshot_t *s) {
    if (!s->procs_valid) { jw_puts(w, "null"); return; }
    unsigned n = s->proc_count < MAX_TOP_PROCS ? s->proc_count : MAX_TOP_PROCS;
    jw_putc(w, '[');
    for (unsigned i = 0; i < n; i++) {
        const proc_stats_t *p = &s->procs[i];
        if (i) jw_putc(w, ',');
        jw_puts(w, "{\"pid\":");         jw_u64(w, p->pid);
        jw_puts(w, ",\"name\":");        jw_str_n(w, p->name, sizeof p->name);
        jw_puts(w, ",\"cpu_pct\":");     jw_dbl(w, (double)p->cpu_pct, 1);
        jw_puts(w, ",\"rss_bytes\":");   jw_u64(w, p->rss_bytes);
        jw_putc(w, '}');
    }
    jw_putc(w, ']');
}

/* The fields shared by snapshot and history entries (no braces, no leading comma). */
static void write_sections(jw_t *w, const snapshot_t *s, bool with_procs) {
    jw_puts(w, "\"seq\":");         jw_u64(w, s->seq);
    jw_puts(w, ",\"ts_ms\":");      jw_u64(w, s->ts_ms);
    jw_puts(w, ",\"warming_up\":"); jw_bool(w, s->warming_up);
    jw_puts(w, ",\"cpu\":");        write_cpu(w, &s->cpu);
    jw_puts(w, ",\"mem\":");        write_mem(w, &s->mem);
    jw_puts(w, ",\"disks\":");      write_disks(w, s);
    if (with_procs) {
        jw_puts(w, ",\"top_procs\":");
        write_procs(w, s);
    }
}

/* =========================================================================
   Public writers
   ========================================================================= */

int proto_write_hello(char *buf, size_t cap, const proto_hello_t *h) {
    jw_t w; jw_init(&w, buf, cap);
    begin_msg(&w, "hello");
    jw_puts(&w, ",\"daemon\":");           jw_str(&w, h->daemon_version);
    jw_puts(&w, ",\"cores\":");            jw_u64(&w, h->cores);
    jw_puts(&w, ",\"interval_ms\":");      jw_u64(&w, h->interval_ms);
    jw_puts(&w, ",\"history_capacity\":"); jw_u64(&w, h->history_capacity);
    jw_putc(&w, '}');
    return jw_finish(&w);
}

int proto_write_snapshot(char *buf, size_t cap, const snapshot_t *s, uint64_t dropped) {
    jw_t w; jw_init(&w, buf, cap);
    begin_msg(&w, "snapshot");
    jw_putc(&w, ',');
    write_sections(&w, s, true);
    jw_puts(&w, ",\"dropped\":");
    jw_u64(&w, dropped);
    jw_putc(&w, '}');
    return jw_finish(&w);
}

int proto_write_history(char *buf, size_t cap, const snapshot_t *snaps, size_t n, bool last) {
    jw_t w; jw_init(&w, buf, cap);
    begin_msg(&w, "history");
    jw_puts(&w, ",\"count\":");  jw_u64(&w, n);
    jw_puts(&w, ",\"last\":");   jw_bool(&w, last);
    jw_puts(&w, ",\"snapshots\":[");
    for (size_t i = 0; i < n; i++) {
        if (i) jw_putc(&w, ',');
        jw_putc(&w, '{');
        write_sections(&w, &snaps[i], false);
        jw_putc(&w, '}');
    }
    jw_puts(&w, "]}");
    return jw_finish(&w);
}

int proto_write_error(char *buf, size_t cap, const char *code, const char *message) {
    jw_t w; jw_init(&w, buf, cap);
    begin_msg(&w, "error");
    jw_puts(&w, ",\"code\":");    jw_str(&w, code);
    jw_puts(&w, ",\"message\":"); jw_str(&w, message);
    jw_putc(&w, '}');
    return jw_finish(&w);
}

int proto_write_pong(char *buf, size_t cap, bool has_id, int64_t id) {
    jw_t w; jw_init(&w, buf, cap);
    begin_msg(&w, "pong");
    if (has_id) { jw_puts(&w, ",\"id\":"); jw_i64(&w, id); }
    jw_putc(&w, '}');
    return jw_finish(&w);
}

/* =========================================================================
   Command parser: a bounded scanner over exactly [p, end). It never looks
   past `end`, never allocates, and recurses at most PROTO_MAX_DEPTH levels.
   ========================================================================= */

#define PROTO_MAX_DEPTH 8
#define KEY_MAX         32

typedef struct { const char *p; const char *end; } scan_t;
typedef struct { bool is_int; bool overflow; bool neg; uint64_t mag; } num_t;
typedef struct { bool present; bool is_number; num_t n; } field_t;

static void skip_ws(scan_t *s) {
    while (s->p < s->end &&
           (*s->p == ' ' || *s->p == '\t' || *s->p == '\r' || *s->p == '\n'))
        s->p++;
}

static bool expect(scan_t *s, char c) {
    skip_ws(s);
    if (s->p < s->end && *s->p == c) { s->p++; return true; }
    return false;
}

static int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Scans a string starting at its opening quote. With out != NULL, stores the
   decoded value, folded to ASCII: a non-ASCII \u escape becomes '?', since no
   protocol keyword contains such a character. A value too long for `out`
   becomes "", which matches nothing. Pass out == NULL to just validate and skip. */
static bool scan_string(scan_t *s, char *out, size_t out_cap) {
    if (s->p >= s->end || *s->p != '"') return false;
    s->p++;
    size_t n = 0;
    bool truncated = false;

    for (;;) {
        if (s->p >= s->end) return false;                   /* unterminated */
        unsigned char c = (unsigned char)*s->p++;
        if (c == '"') break;
        if (c < 0x20) return false;                         /* raw control characters are illegal */

        char ch = (char)c;
        if (c == '\\') {
            if (s->p >= s->end) return false;
            char e = *s->p++;
            switch (e) {
            case '"':  ch = '"';  break;
            case '\\': ch = '\\'; break;
            case '/':  ch = '/';  break;
            case 'b':  ch = '\b'; break;
            case 'f':  ch = '\f'; break;
            case 'n':  ch = '\n'; break;
            case 'r':  ch = '\r'; break;
            case 't':  ch = '\t'; break;
            case 'u': {
                if (s->end - s->p < 4) return false;
                unsigned v = 0;
                for (int i = 0; i < 4; i++) {
                    int h = hex_val(s->p[i]);
                    if (h < 0) return false;
                    v = v * 16 + (unsigned)h;
                }
                s->p += 4;
                ch = (v != 0 && v < 0x80) ? (char)v : '?';
                break;
            }
            default: return false;
            }
        }
        if (out) {
            if (n + 1 < out_cap) out[n++] = ch;
            else truncated = true;
        }
    }
    if (out) { if (truncated) n = 0; out[n] = '\0'; }
    return true;
}

/* Validates JSON number syntax; also records the value when it is a plain integer. */
static bool scan_number(scan_t *s, num_t *out) {
    const char *p = s->p;
    num_t r = { .is_int = true };

    if (p < s->end && *p == '-') { r.neg = true; p++; }
    if (p >= s->end) return false;

    if (*p == '0') {
        p++;                                                /* "01" is not JSON: the 1 fails later */
    } else if (*p >= '1' && *p <= '9') {
        while (p < s->end && *p >= '0' && *p <= '9') {
            unsigned d = (unsigned)(*p - '0');
            if (r.mag > (UINT64_MAX - d) / 10) r.overflow = true;
            else r.mag = r.mag * 10 + d;
            p++;
        }
    } else {
        return false;
    }

    if (p < s->end && *p == '.') {
        r.is_int = false; p++;
        if (p >= s->end || *p < '0' || *p > '9') return false;
        while (p < s->end && *p >= '0' && *p <= '9') p++;
    }
    if (p < s->end && (*p == 'e' || *p == 'E')) {
        r.is_int = false; p++;
        if (p < s->end && (*p == '+' || *p == '-')) p++;
        if (p >= s->end || *p < '0' || *p > '9') return false;
        while (p < s->end && *p >= '0' && *p <= '9') p++;
    }
    s->p = p;
    if (out) *out = r;
    return true;
}

static bool scan_literal(scan_t *s, const char *word) {
    size_t n = strlen(word);
    if ((size_t)(s->end - s->p) < n || memcmp(s->p, word, n) != 0) return false;
    s->p += n;
    return true;
}

/* Skips any JSON value (used for fields we do not know), up to a nesting limit. */
static bool skip_value(scan_t *s, int depth) {
    if (depth > PROTO_MAX_DEPTH) return false;
    skip_ws(s);
    if (s->p >= s->end) return false;

    switch (*s->p) {
    case '"': return scan_string(s, NULL, 0);
    case 't': return scan_literal(s, "true");
    case 'f': return scan_literal(s, "false");
    case 'n': return scan_literal(s, "null");
    case '{':
        s->p++;
        skip_ws(s);
        if (s->p < s->end && *s->p == '}') { s->p++; return true; }
        for (;;) {
            skip_ws(s);
            if (!scan_string(s, NULL, 0)) return false;
            if (!expect(s, ':'))          return false;
            if (!skip_value(s, depth + 1)) return false;
            skip_ws(s);
            if (s->p >= s->end) return false;
            if (*s->p == ',') { s->p++; continue; }
            if (*s->p == '}') { s->p++; return true; }
            return false;
        }
    case '[':
        s->p++;
        skip_ws(s);
        if (s->p < s->end && *s->p == ']') { s->p++; return true; }
        for (;;) {
            if (!skip_value(s, depth + 1)) return false;
            skip_ws(s);
            if (s->p >= s->end) return false;
            if (*s->p == ',') { s->p++; continue; }
            if (*s->p == ']') { s->p++; return true; }
            return false;
        }
    default:
        return scan_number(s, NULL);
    }
}

/* A field we want as a number. A value of the wrong type is still skipped (it
   must be valid JSON) and recorded as is_number == false, so the semantic
   check can report bad_value instead of bad_json. */
static bool read_numeric_field(scan_t *s, field_t *f) {
    skip_ws(s);
    f->present = true;
    f->is_number = false;
    if (s->p < s->end && (*s->p == '-' || (*s->p >= '0' && *s->p <= '9'))) {
        if (!scan_number(s, &f->n)) return false;
        f->is_number = true;
        return true;
    }
    return skip_value(s, 1);
}

static bool num_to_u32(const field_t *f, uint32_t *out) {
    if (!f->is_number || !f->n.is_int || f->n.overflow) return false;
    if (f->n.neg && f->n.mag != 0) return false;
    if (f->n.mag > UINT32_MAX) return false;
    *out = (uint32_t)f->n.mag;
    return true;
}

static bool num_to_i64(const field_t *f, int64_t *out) {
    if (!f->is_number || !f->n.is_int || f->n.overflow) return false;
    if (f->n.mag > (uint64_t)INT64_MAX) return false;
    *out = f->n.neg ? -(int64_t)f->n.mag : (int64_t)f->n.mag;
    return true;
}

proto_status_t proto_parse_command(const char *line, size_t len, command_t *out) {
    if (!line || !out) return PROTO_ERR_BAD_JSON;
    if (len > PROTO_MAX_LINE_IN) return PROTO_ERR_TOO_LONG;

    scan_t s = { line, line + len };
    char    type[KEY_MAX] = "";
    bool    type_present = false, type_is_string = false;
    field_t f_v = {0}, f_interval = {0}, f_count = {0}, f_id = {0};

    /* ---- syntax: one flat-ish object, unknown fields skipped ---- */
    if (!expect(&s, '{')) return PROTO_ERR_BAD_JSON;
    skip_ws(&s);
    if (s.p < s.end && *s.p == '}') {
        s.p++;                                              /* {} is valid JSON, but has no type */
    } else {
        for (;;) {
            char key[KEY_MAX];
            skip_ws(&s);
            if (!scan_string(&s, key, sizeof key)) return PROTO_ERR_BAD_JSON;
            if (!expect(&s, ':'))                  return PROTO_ERR_BAD_JSON;
            skip_ws(&s);

            bool ok;
            if (strcmp(key, "type") == 0) {
                type_present = true;
                if (s.p < s.end && *s.p == '"') { type_is_string = true;  ok = scan_string(&s, type, sizeof type); }
                else                            { type_is_string = false; ok = skip_value(&s, 1); }
            }
            else if (strcmp(key, "v") == 0)           ok = read_numeric_field(&s, &f_v);
            else if (strcmp(key, "interval_ms") == 0) ok = read_numeric_field(&s, &f_interval);
            else if (strcmp(key, "count") == 0)       ok = read_numeric_field(&s, &f_count);
            else if (strcmp(key, "id") == 0)          ok = read_numeric_field(&s, &f_id);
            else                                      ok = skip_value(&s, 1);
            if (!ok) return PROTO_ERR_BAD_JSON;

            skip_ws(&s);
            if (s.p >= s.end) return PROTO_ERR_BAD_JSON;
            if (*s.p == ',') { s.p++; continue; }
            if (*s.p == '}') { s.p++; break; }
            return PROTO_ERR_BAD_JSON;
        }
    }
    skip_ws(&s);
    if (s.p != s.end) return PROTO_ERR_BAD_JSON;            /* trailing garbage */

    /* ---- meaning: only checked once the syntax is fully valid ---- */
    if (f_v.present) {
        int64_t ver;
        if (!num_to_i64(&f_v, &ver) || ver != PROTO_VERSION) return PROTO_ERR_BAD_VERSION;
    }
    if (!type_present)   return PROTO_ERR_MISSING_FIELD;
    if (!type_is_string) return PROTO_ERR_BAD_VALUE;

    command_t cmd;
    memset(&cmd, 0, sizeof cmd);

    if (strcmp(type, "ping") == 0) {
        cmd.type = CMD_PING;
        if (f_id.present) {
            if (!num_to_i64(&f_id, &cmd.id)) return PROTO_ERR_BAD_VALUE;
            cmd.has_id = true;
        }
    } else if (strcmp(type, "set_interval") == 0) {
        if (!f_interval.present) return PROTO_ERR_MISSING_FIELD;
        if (!num_to_u32(&f_interval, &cmd.interval_ms)) return PROTO_ERR_BAD_VALUE;
        cmd.type = CMD_SET_INTERVAL;
    } else if (strcmp(type, "get_history") == 0) {
        cmd.type = CMD_GET_HISTORY;
        if (f_count.present &&
            (!num_to_u32(&f_count, &cmd.count) || cmd.count == 0))
            return PROTO_ERR_BAD_VALUE;
    } else {
        return PROTO_ERR_UNKNOWN_TYPE;
    }

    *out = cmd;
    return PROTO_OK;
}

const char *proto_status_code(proto_status_t s) {
    switch (s) {
    case PROTO_OK:                return "ok";
    case PROTO_ERR_BAD_JSON:      return "bad_json";
    case PROTO_ERR_BAD_VERSION:   return "bad_version";
    case PROTO_ERR_UNKNOWN_TYPE:  return "unknown_type";
    case PROTO_ERR_MISSING_FIELD: return "missing_field";
    case PROTO_ERR_BAD_VALUE:     return "bad_value";
    case PROTO_ERR_TOO_LONG:      return "line_too_long";
    }
    return "internal";
}

const char *proto_status_message(proto_status_t s) {
    switch (s) {
    case PROTO_OK:                return "ok";
    case PROTO_ERR_BAD_JSON:      return "The line is not valid JSON.";
    case PROTO_ERR_BAD_VERSION:   return "Unsupported protocol version.";
    case PROTO_ERR_UNKNOWN_TYPE:  return "Unknown command type.";
    case PROTO_ERR_MISSING_FIELD: return "A required field is missing.";
    case PROTO_ERR_BAD_VALUE:     return "A field has the wrong type or is out of range.";
    case PROTO_ERR_TOO_LONG:      return "The line is too long.";
    }
    return "Internal error.";
}