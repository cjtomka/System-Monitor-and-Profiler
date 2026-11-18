#include "proto.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_run = 0, g_fail = 0;
#define CHECK(cond, ...) do {                                              \
        g_run++;                                                           \
        if (!(cond)) { g_fail++; printf("  FAIL  "); printf(__VA_ARGS__);  \
                       printf("  (line %d)\n", __LINE__); }                \
    } while (0)

static bool has(const char *hay, const char *needle) { return strstr(hay, needle) != NULL; }

static int count_of(const char *hay, const char *needle) {
    int n = 0;
    for (const char *p = hay; (p = strstr(p, needle)) != NULL; p += strlen(needle)) n++;
    return n;
}

static void make_snapshot(snapshot_t *s) {
    memset(s, 0, sizeof *s);
    s->seq = 7;
    s->ts_ms = 1763302801002ULL;

    s->cpu.valid = true;
    s->cpu.total_pct = 37.5;
    s->cpu.core_count = 2;
    s->cpu.cores_pct[0] = 41.0f;
    s->cpu.cores_pct[1] = 33.2f;

    s->mem.valid = true;
    s->mem.total_bytes = 17179869184ULL;
    s->mem.avail_bytes = 6442450944ULL;
    s->mem.commit_bytes = 12884901888ULL;
    s->mem.commit_limit_bytes = 25769803776ULL;

    s->disks_valid = true;
    s->disk_count = 1;
    strcpy(s->disks[0].name, "C:");
    s->disks[0].total_bytes = 512110190592ULL;
    s->disks[0].free_bytes = 201326592000ULL;
    s->disks[0].read_bps = 1048576;
    s->disks[0].write_bps = 262144;
    s->disks[0].queue = 0.4;

    s->procs_valid = true;
    s->proc_count = 1;
    s->procs[0].pid = 4120;
    strcpy(s->procs[0].name, "chrome.exe");
    s->procs[0].cpu_pct = 12.1f;
    s->procs[0].rss_bytes = 412000000ULL;
}

static char g_buf[PROTO_MAX_MSG_OUT];

/* ---- writers ------------------------------------------------------------ */

static void test_snapshot_exact(void) {
    snapshot_t s; make_snapshot(&s);
    const char *want =
        "{\"v\":1,\"type\":\"snapshot\",\"seq\":7,\"ts_ms\":1763302801002,\"warming_up\":false,"
        "\"cpu\":{\"total_pct\":37.5,\"cores_pct\":[41.0,33.2]},"
        "\"mem\":{\"total_bytes\":17179869184,\"avail_bytes\":6442450944,"
        "\"commit_bytes\":12884901888,\"commit_limit_bytes\":25769803776},"
        "\"disks\":[{\"name\":\"C:\",\"total_bytes\":512110190592,\"free_bytes\":201326592000,"
        "\"read_bps\":1048576,\"write_bps\":262144,\"queue\":0.40}],"
        "\"top_procs\":[{\"pid\":4120,\"name\":\"chrome.exe\",\"cpu_pct\":12.1,\"rss_bytes\":412000000}],"
        "\"dropped\":3}\n";
    int n = proto_write_snapshot(g_buf, sizeof g_buf, &s, 3);
    CHECK(n == (int)strlen(want), "snapshot length: got %d, want %zu", n, strlen(want));
    CHECK(strcmp(g_buf, want) == 0, "snapshot text differs:\n%s", g_buf);
}

static void test_null_and_empty_sections(void) {
    snapshot_t s;
    memset(&s, 0, sizeof s);
    s.seq = 1; s.ts_ms = 2; s.warming_up = true;
    int n = proto_write_snapshot(g_buf, sizeof g_buf, &s, 0);
    CHECK(n > 0 && strcmp(g_buf,
        "{\"v\":1,\"type\":\"snapshot\",\"seq\":1,\"ts_ms\":2,\"warming_up\":true,"
        "\"cpu\":null,\"mem\":null,\"disks\":null,\"top_procs\":null,\"dropped\":0}\n") == 0,
        "all-invalid snapshot: %s", g_buf);

    s.cpu.valid = true; s.disks_valid = true; s.procs_valid = true;      /* valid but empty */
    proto_write_snapshot(g_buf, sizeof g_buf, &s, 0);
    CHECK(has(g_buf, "\"cores_pct\":[]"), "empty cores: %s", g_buf);
    CHECK(has(g_buf, "\"disks\":[]"),     "empty disks: %s", g_buf);
    CHECK(has(g_buf, "\"top_procs\":[]"), "empty procs: %s", g_buf);
}

static void test_nonfinite_numbers(void) {
    snapshot_t s; make_snapshot(&s);
    s.cpu.total_pct = NAN;
    s.disks[0].queue = INFINITY;
    s.procs[0].cpu_pct = 1e30f;
    int n = proto_write_snapshot(g_buf, sizeof g_buf, &s, 0);
    CHECK(n > 0, "non-finite values must not fail the write");
    CHECK(has(g_buf, "\"total_pct\":null"), "NaN -> null: %s", g_buf);
    CHECK(has(g_buf, "\"queue\":null"),     "Inf -> null: %s", g_buf);
    CHECK(has(g_buf, "\"cpu_pct\":1000000015047466219876688855040.0") == false,
          "huge value must not print as digits");
    CHECK(has(g_buf, "\"cpu_pct\":null"), "huge -> null: %s", g_buf);
}

static void test_string_escaping(void) {
    snapshot_t s;
    memset(&s, 0, sizeof s);
    s.procs_valid = true; s.proc_count = 1; s.procs[0].pid = 1;
    /* quote, backslash, control char, valid 2-byte char, then three kinds of invalid UTF-8 */
    strcpy(s.procs[0].name, "a\"b\\c\x01" "\xC3\xA9" "\xFF" "\xC0\x80" "z");
    proto_write_snapshot(g_buf, sizeof g_buf, &s, 0);
    const char *want = "\"name\":\"a\\\"b\\\\c\\u0001" "\xC3\xA9"
                       "\xEF\xBF\xBD" "\xEF\xBF\xBD" "\xEF\xBF\xBD" "z\"";
    CHECK(has(g_buf, want), "escaped name wrong: %s", g_buf);

    memset(&s.procs[0].name, 0, sizeof s.procs[0].name);
    strcpy(s.procs[0].name, "\xED\xA0\x80");                 /* encoded surrogate: invalid */
    proto_write_snapshot(g_buf, sizeof g_buf, &s, 0);
    CHECK(has(g_buf, "\"name\":\"" "\xEF\xBF\xBD\xEF\xBF\xBD\xEF\xBF\xBD" "\""), "surrogate: %s", g_buf);
}

static void test_counts_are_clamped(void) {
    snapshot_t s; make_snapshot(&s);
    s.cpu.core_count = 500; s.disk_count = 255; s.proc_count = 255;      /* corrupt counts */
    int n = proto_write_snapshot(g_buf, sizeof g_buf, &s, 0);
    CHECK(n > 0, "corrupt counts must still serialize");
    CHECK(count_of(g_buf, "\"read_bps\":") == MAX_DISKS, "disks clamped to %d", MAX_DISKS);
    CHECK(count_of(g_buf, "\"pid\":") == MAX_TOP_PROCS, "procs clamped to %d", MAX_TOP_PROCS);
}

static void test_history(void) {
    snapshot_t snaps[3];
    for (int i = 0; i < 3; i++) { make_snapshot(&snaps[i]); snaps[i].seq = (uint64_t)(i + 1); }
    int n = proto_write_history(g_buf, sizeof g_buf, snaps, 3, true);
    CHECK(n > 0, "history write failed");

    const char *prefix =
        "{\"v\":1,\"type\":\"history\",\"count\":3,\"last\":true,\"snapshots\":[{\"seq\":1,";
    CHECK(strncmp(g_buf, prefix, strlen(prefix)) == 0, "history prefix: %.90s", g_buf);
    CHECK(count_of(g_buf, "\"seq\":") == 3, "three entries");
    CHECK(!has(g_buf, "top_procs"), "history must not carry the process list");
    CHECK(n >= 3 && strcmp(g_buf + n - 3, "]}\n") == 0, "history suffix");

    proto_write_history(g_buf, sizeof g_buf, snaps, 0, false);
    CHECK(has(g_buf, "\"count\":0,\"last\":false,\"snapshots\":[]}"), "empty history: %s", g_buf);
}

static void test_small_messages(void) {
    proto_hello_t h = { "monitord 0.1.0", 8, 1000, 300 };
    proto_write_hello(g_buf, sizeof g_buf, &h);
    CHECK(strcmp(g_buf, "{\"v\":1,\"type\":\"hello\",\"daemon\":\"monitord 0.1.0\",\"cores\":8,"
                        "\"interval_ms\":1000,\"history_capacity\":300}\n") == 0, "hello: %s", g_buf);

    proto_write_error(g_buf, sizeof g_buf, "bad_json", "The line is not valid JSON.");
    CHECK(strcmp(g_buf, "{\"v\":1,\"type\":\"error\",\"code\":\"bad_json\","
                        "\"message\":\"The line is not valid JSON.\"}\n") == 0, "error: %s", g_buf);

    proto_write_pong(g_buf, sizeof g_buf, false, 0);
    CHECK(strcmp(g_buf, "{\"v\":1,\"type\":\"pong\"}\n") == 0, "pong: %s", g_buf);
    proto_write_pong(g_buf, sizeof g_buf, true, 42);
    CHECK(strcmp(g_buf, "{\"v\":1,\"type\":\"pong\",\"id\":42}\n") == 0, "pong with id: %s", g_buf);
}

/* Every too-small capacity must fail cleanly and never touch a byte past cap. */
static void test_overflow_is_safe(void) {
    snapshot_t s; make_snapshot(&s);
    int need = proto_write_snapshot(g_buf, sizeof g_buf, &s, 3);
    unsigned char raw[4096];
    CHECK(need > 0 && (size_t)need + 32 < sizeof raw, "fixture size sanity");
    if (need <= 0 || (size_t)need + 32 >= sizeof raw) return;

    for (size_t cap = 0; cap <= (size_t)need; cap++) {        /* cap == need leaves no room for the NUL */
        memset(raw, 0xA5, sizeof raw);
        int r = proto_write_snapshot((char *)raw, cap, &s, 3);
        CHECK(r == -1, "cap %zu: expected -1, got %d", cap, r);
        for (size_t i = cap; i < cap + 16; i++)
            CHECK(raw[i] == 0xA5, "cap %zu: wrote past the end (byte %zu)", cap, i);
    }
    memset(raw, 0xA5, sizeof raw);
    int r = proto_write_snapshot((char *)raw, (size_t)need + 1, &s, 3);
    CHECK(r == need && raw[need] == '\0', "exact-fit capacity must succeed");
    CHECK(proto_write_snapshot(NULL, 0, &s, 0) == -1, "NULL buffer must fail, not crash");
}

static void make_worst(snapshot_t *s) {
    memset(s, 0, sizeof *s);
    s->seq = UINT64_MAX; s->ts_ms = UINT64_MAX; s->warming_up = true;
    s->cpu.valid = true; s->cpu.total_pct = 100.0; s->cpu.core_count = MAX_CORES;
    for (int i = 0; i < MAX_CORES; i++) s->cpu.cores_pct[i] = 100.0f;
    s->mem.valid = true;
    s->mem.total_bytes = s->mem.avail_bytes = s->mem.commit_bytes = s->mem.commit_limit_bytes = UINT64_MAX;
    s->disks_valid = true; s->disk_count = MAX_DISKS;
    for (int i = 0; i < MAX_DISKS; i++) {
        memset(s->disks[i].name, 1, sizeof s->disks[i].name - 1);      /* each byte escapes to 6 */
        s->disks[i].total_bytes = s->disks[i].free_bytes = UINT64_MAX;
        s->disks[i].read_bps = s->disks[i].write_bps = UINT64_MAX;
        s->disks[i].queue = 99999.99;
    }
    s->procs_valid = true; s->proc_count = MAX_TOP_PROCS;
    for (int i = 0; i < MAX_TOP_PROCS; i++) {
        s->procs[i].pid = UINT32_MAX;
        memset(s->procs[i].name, 1, sizeof s->procs[i].name - 1);
        s->procs[i].cpu_pct = 100.0f;
        s->procs[i].rss_bytes = UINT64_MAX;
    }
}

static void test_worst_case_fits(void) {
    snapshot_t w; make_worst(&w);
    int n = proto_write_snapshot(g_buf, sizeof g_buf, &w, UINT64_MAX);
    CHECK(n > 0 && n < PROTO_MAX_MSG_OUT, "worst-case snapshot must fit (got %d)", n);
    printf("  worst-case snapshot: %d bytes (limit %d)\n", n, PROTO_MAX_MSG_OUT);

    snapshot_t chunk[PROTO_HISTORY_CHUNK];
    for (int i = 0; i < PROTO_HISTORY_CHUNK; i++) chunk[i] = w;
    n = proto_write_history(g_buf, sizeof g_buf, chunk, PROTO_HISTORY_CHUNK, true);
    CHECK(n > 0 && n < PROTO_MAX_MSG_OUT, "worst-case history chunk must fit (got %d)", n);
    printf("  worst-case history chunk: %d bytes (limit %d)\n", n, PROTO_MAX_MSG_OUT);
}

/* ---- parser ------------------------------------------------------------- */

static void expect_parse(const char *line, proto_status_t want) {
    command_t c;
    proto_status_t got = proto_parse_command(line, strlen(line), &c);
    CHECK(got == want, "parse(%.60s): want %s, got %s", line,
          proto_status_code(want), proto_status_code(got));
}

static void test_parse_valid(void) {
    command_t c;

    CHECK(proto_parse_command("{\"type\":\"ping\"}", 15, &c) == PROTO_OK &&
          c.type == CMD_PING && !c.has_id, "bare ping");

    const char *p2 = "{\"v\":1,\"type\":\"ping\",\"id\":42}";
    CHECK(proto_parse_command(p2, strlen(p2), &c) == PROTO_OK && c.has_id && c.id == 42, "ping with id");

    const char *p3 = "{\"type\":\"set_interval\",\"interval_ms\":500}";
    CHECK(proto_parse_command(p3, strlen(p3), &c) == PROTO_OK &&
          c.type == CMD_SET_INTERVAL && c.interval_ms == 500, "set_interval");

    const char *p4 = "{\"type\":\"get_history\",\"count\":120}";
    CHECK(proto_parse_command(p4, strlen(p4), &c) == PROTO_OK &&
          c.type == CMD_GET_HISTORY && c.count == 120, "get_history with count");

    const char *p5 = "{\"type\":\"get_history\"}";
    CHECK(proto_parse_command(p5, strlen(p5), &c) == PROTO_OK && c.count == 0, "get_history without count = all");

    expect_parse("  { \"type\" : \"ping\" }  ", PROTO_OK);                               /* whitespace */
    expect_parse("{\"type\":\"ping\",\"extra\":{\"a\":[1,2,{\"b\":null}]},\"x\":\"y\"}", PROTO_OK); /* unknown fields */
    expect_parse("{\"type\":\"ping\",\"id\":1,\"id\":2}", PROTO_OK);                    /* duplicate key: last wins */
    expect_parse("{\"type\":\"set_interval\",\"interval_ms\":500,\"count\":\"junk\"}", PROTO_OK); /* unused field ignored */

    const char *esc = "{\"type\":\"p\\u0069ng\"}";                                      /* \u0069 == 'i' */
    CHECK(proto_parse_command(esc, strlen(esc), &c) == PROTO_OK && c.type == CMD_PING, "\\u escape in type");
}

static void test_parse_errors(void) {
    expect_parse("", PROTO_ERR_BAD_JSON);
    expect_parse("{", PROTO_ERR_BAD_JSON);
    expect_parse("{\"type\":\"ping\"", PROTO_ERR_BAD_JSON);
    expect_parse("{\"type\":\"ping\"} x", PROTO_ERR_BAD_JSON);       /* trailing garbage */
    expect_parse("{\"type\":\"ping\",}", PROTO_ERR_BAD_JSON);        /* trailing comma */
    expect_parse("{\"type\":ping}", PROTO_ERR_BAD_JSON);
    expect_parse("[1,2]", PROTO_ERR_BAD_JSON);
    expect_parse("{\"type\":\"ping\",\"id\":01}", PROTO_ERR_BAD_JSON);   /* leading zero */
    expect_parse("{\"type\":\"pi\ng\"}", PROTO_ERR_BAD_JSON);        /* raw control character in a string */

    expect_parse("{}", PROTO_ERR_MISSING_FIELD);
    expect_parse("{\"id\":1}", PROTO_ERR_MISSING_FIELD);
    expect_parse("{\"type\":\"set_interval\"}", PROTO_ERR_MISSING_FIELD);

    expect_parse("{\"type\":5}", PROTO_ERR_BAD_VALUE);
    expect_parse("{\"type\":\"set_interval\",\"interval_ms\":\"fast\"}", PROTO_ERR_BAD_VALUE);
    expect_parse("{\"type\":\"set_interval\",\"interval_ms\":-5}", PROTO_ERR_BAD_VALUE);
    expect_parse("{\"type\":\"set_interval\",\"interval_ms\":1.5}", PROTO_ERR_BAD_VALUE);
    expect_parse("{\"type\":\"set_interval\",\"interval_ms\":1e3}", PROTO_ERR_BAD_VALUE);
    expect_parse("{\"type\":\"set_interval\",\"interval_ms\":99999999999}", PROTO_ERR_BAD_VALUE);
    expect_parse("{\"type\":\"get_history\",\"count\":0}", PROTO_ERR_BAD_VALUE);
    expect_parse("{\"type\":\"ping\",\"id\":\"seven\"}", PROTO_ERR_BAD_VALUE);

    expect_parse("{\"type\":\"reboot\"}", PROTO_ERR_UNKNOWN_TYPE);
    expect_parse("{\"type\":\"abcdefghijklmnopqrstuvwxyzabcdefghijkl\"}", PROTO_ERR_UNKNOWN_TYPE);   /* longer than KEY_MAX */
    expect_parse("{\"v\":2,\"type\":\"ping\"}", PROTO_ERR_BAD_VERSION);
    expect_parse("{\"v\":\"1\",\"type\":\"ping\"}", PROTO_ERR_BAD_VERSION);

    char deep[256];                                                    /* nesting far beyond the cap */
    strcpy(deep, "{\"type\":\"ping\",\"x\":");
    size_t n = strlen(deep);
    for (int i = 0; i < 20; i++) deep[n++] = '[';
    for (int i = 0; i < 20; i++) deep[n++] = ']';
    deep[n++] = '}'; deep[n] = '\0';
    expect_parse(deep, PROTO_ERR_BAD_JSON);

    static char big[2048];                                             /* valid JSON, but over the line limit */
    strcpy(big, "{\"type\":\"ping\",\"pad\":\"");
    n = strlen(big);
    memset(big + n, 'a', 1500); n += 1500;
    strcpy(big + n, "\"}");
    expect_parse(big, PROTO_ERR_TOO_LONG);
}

/* Parse random and mutated input from exactly-sized heap blocks, so under
   AddressSanitizer any read past `len` is caught. The only assertion here is
   that nothing crashes and the status is a known value. */
static uint32_t g_rng = 12345;
static uint32_t rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 17; g_rng ^= g_rng << 5; return g_rng; }

static void fuzz_one(const char *src, size_t len, int *bad) {
    char *copy = malloc(len ? len : 1);
    if (!copy) return;
    if (len) memcpy(copy, src, len);
    command_t c;
    proto_status_t st = proto_parse_command(copy, len, &c);
    if ((int)st < 0 || st > PROTO_ERR_TOO_LONG) (*bad)++;
    if (st == PROTO_OK && c.type != CMD_PING && c.type != CMD_SET_INTERVAL && c.type != CMD_GET_HISTORY) (*bad)++;
    free(copy);
}

static void test_fuzz(void) {
    static const char alphabet[] = "{}[]\":,-+.0123456789eE abcdefghijklmnopqrstuvwxyz\\_ntu";
    int bad = 0;
    char line[256];

    for (int iter = 0; iter < 50000; iter++) {
        size_t len = rnd() % sizeof line;
        for (size_t i = 0; i < len; i++) line[i] = alphabet[rnd() % (sizeof alphabet - 1)];
        fuzz_one(line, len, &bad);
    }

    const char *seeds[] = {
        "{\"v\":1,\"type\":\"ping\",\"id\":42}",
        "{\"type\":\"set_interval\",\"interval_ms\":500}",
        "{\"type\":\"get_history\",\"count\":120,\"extra\":{\"a\":[1,2,3]}}",
    };
    for (int iter = 0; iter < 50000; iter++) {
        const char *seed = seeds[rnd() % 3];
        size_t len = strlen(seed);
        memcpy(line, seed, len);
        for (unsigned k = rnd() % 3 + 1; k > 0; k--) line[rnd() % len] = (char)(rnd() & 0xFF);
        if (rnd() % 4 == 0) len = rnd() % (len + 1);                  /* sometimes truncate */
        fuzz_one(line, len, &bad);
    }
    CHECK(bad == 0, "fuzz: %d invalid results", bad);
}

int main(void) {
    test_snapshot_exact();
    test_null_and_empty_sections();
    test_nonfinite_numbers();
    test_string_escaping();
    test_counts_are_clamped();
    test_history();
    test_small_messages();
    test_overflow_is_safe();
    test_worst_case_fits();
    test_parse_valid();
    test_parse_errors();
    test_fuzz();

    printf("%d checks, %d failed\n", g_run, g_fail);
    return g_fail ? 1 : 0;
}