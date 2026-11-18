/* monctl: small debug client for monitord. Connects to the AF_UNIX socket,
   optionally sends commands, and prints every line the daemon sends. */

#include <winsock2.h>
#include <afunix.h>
#include <windows.h>

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sockpath.h"

#define BUF_SZ     (128 * 1024)       /* the daemon never sends a line over 64 KB */
#define MAX_SENDS  8
#define SEND_CAP   2200

typedef unsigned long long u64;

static volatile LONG g_stop = 0;
static BOOL WINAPI on_ctrl(DWORD t) {
    if (t == CTRL_C_EVENT || t == CTRL_BREAK_EVENT) { InterlockedExchange(&g_stop, 1); return TRUE; }
    return FALSE;
}

static char g_sends[MAX_SENDS][SEND_CAP];
static int  g_nsend = 0;

static bool add_send(const char *line) {
    if (g_nsend >= MAX_SENDS || strlen(line) >= SEND_CAP) return false;
    strcpy(g_sends[g_nsend++], line);
    return true;
}

static bool add_fmt(const char *fmt, unsigned long v) {
    char tmp[128];
    snprintf(tmp, sizeof tmp, fmt, v);
    return add_send(tmp);
}

static void usage(void) {
    fprintf(stderr,
        "usage: monctl [options]\n"
        "  --socket PATH     socket to connect to (default: the per-user path monitord uses)\n"
        "  --ping            send a ping\n"
        "  --interval MS     send set_interval\n"
        "  --history N       ask for the last N snapshots (0 = everything the daemon has)\n"
        "  --until-last      with --history: exit when the final history chunk arrives\n"
        "  --send LINE       send a raw line (repeatable, up to %d)\n"
        "  --long-line       send a 1.5 KB line, to test the daemon's line limit\n"
        "  --brief           one summary line per message instead of the full JSON\n"
        "  --count N         exit after N lines\n"
        "  --timeout S       exit after S seconds\n"
        "  --stall S         connect, then do not read for S seconds (tests backpressure)\n", MAX_SENDS);
}

static bool parse_ul(const char *s, unsigned long *out) {
    if (!s || !*s || *s == '-') return false;
    char *end;
    *out = strtoul(s, &end, 10);
    return *end == '\0';
}

/* ---- statistics, printed to stderr at exit ------------------------------ */

typedef struct {
    long lines;
    long snaps;
    u64  last_snap_seq;                /* 0 = none yet */
    u64  missing;                      /* snapshots skipped, counted from seq gaps */
    u64  last_dropped;                 /* the daemon's own counter, as of the newest snapshot */

    long hist_chunks;
    u64  hist_total, hist_first, hist_prev;
    bool hist_gap;
    u64  hist_gap_at;
    bool hist_last_seen;
} stats_t;

static bool find_u64(const char *line, const char *key, u64 *out) {
    const char *p = strstr(line, key);
    if (!p) return false;
    *out = strtoull(p + strlen(key), NULL, 10);
    return true;
}

static void get_type(const char *line, char *type, size_t cap) {
    type[0] = '?'; type[1] = '\0';
    const char *t = strstr(line, "\"type\":\"");
    if (!t) return;
    t += 8;
    size_t k = 0;
    while (t[k] && t[k] != '"' && k + 1 < cap) { type[k] = t[k]; k++; }
    type[k] = '\0';
}

/* Prints one received line and updates the statistics. `line` is NUL-terminated.
   Returns true if this was the final chunk of a history reply. */
static bool process_line(char *line, size_t len, bool brief, stats_t *st) {
    if (len && line[len - 1] == '\r') line[--len] = '\0';

    char type[16];
    get_type(line, type, sizeof type);
    char note[64] = "";
    bool final_chunk = false;
    u64 v, chunk_n = 0, chunk_first = 0, chunk_last = 0;

    if (!strcmp(type, "snapshot")) {
        if (find_u64(line, "\"seq\":", &v)) {
            if (st->last_snap_seq && v > st->last_snap_seq + 1) {
                u64 gap = v - st->last_snap_seq - 1;
                st->missing += gap;
                snprintf(note, sizeof note, "  GAP: %llu missing", gap);
                fprintf(stderr, "monctl: %llu snapshots missing before seq %llu\n", gap, v);
            }
            st->last_snap_seq = v;
            st->snaps++;
        }
        if (find_u64(line, "\"dropped\":", &v)) st->last_dropped = v;
    } else if (!strcmp(type, "history")) {
        st->hist_chunks++;
        for (const char *p = line; (p = strstr(p, "\"seq\":")) != NULL; ) {
            p += 6;
            u64 seq = strtoull(p, NULL, 10);
            if (st->hist_total == 0) st->hist_first = seq;
            else if (seq != st->hist_prev + 1 && !st->hist_gap) { st->hist_gap = true; st->hist_gap_at = seq; }
            st->hist_prev = seq;
            st->hist_total++;
            if (chunk_n++ == 0) chunk_first = seq;
            chunk_last = seq;
        }
        if (strstr(line, "\"last\":true")) { final_chunk = true; st->hist_last_seen = true; }
    }

    if (!brief) {
        fputs(line, stdout);
        fputc('\n', stdout);
        fflush(stdout);
        return final_chunk;
    }

    printf("%-9s %6zu bytes", type, len);
    if (!strcmp(type, "snapshot")) {
        if (find_u64(line, "\"seq\":", &v))     printf("  seq=%llu", v);
        if (find_u64(line, "\"dropped\":", &v)) printf("  dropped=%llu", v);
    } else if (!strcmp(type, "hello")) {
        if (find_u64(line, "\"interval_ms\":", &v)) printf("  interval_ms=%llu", v);
    } else if (!strcmp(type, "history")) {
        printf("  count=%llu  seq=%llu..%llu%s", chunk_n, chunk_first, chunk_last,
               final_chunk ? "  last" : "");
    } else if (!strcmp(type, "error")) {
        printf("  %s", line);
    }
    printf("%s\n", note);
    fflush(stdout);
    return final_chunk;
}

static void print_summary(const stats_t *st) {
    fprintf(stderr, "monctl: %ld lines", st->lines);
    if (st->snaps)
        fprintf(stderr, "; %ld snapshots, %llu missing (seq gaps), daemon's dropped counter at the last one: %llu",
                st->snaps, st->missing, st->last_dropped);
    fputc('\n', stderr);

    if (st->hist_chunks) {
        if (st->hist_total == 0) {
            fprintf(stderr, "monctl: history: %ld chunks, 0 snapshots (the daemon had none yet)", st->hist_chunks);
        } else {
            fprintf(stderr, "monctl: history: %ld chunks, %llu snapshots, seq %llu..%llu, ",
                    st->hist_chunks, st->hist_total, st->hist_first, st->hist_prev);
            if (st->hist_gap) fprintf(stderr, "NOT contiguous (gap at seq %llu)", st->hist_gap_at);
            else              fprintf(stderr, "contiguous");
        }
        if (!st->hist_last_seen) fprintf(stderr, "; the final chunk (last) never arrived");
        fputc('\n', stderr);
    }
}

/* ---- main --------------------------------------------------------------- */

int main(int argc, char **argv) {
    const char *path = NULL;
    long   max_lines = 0;
    double timeout_s = 0, stall_s = 0;
    bool   brief = false, until_last = false, asked_history = false;

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        bool more = (i + 1 < argc);
        unsigned long v;
        bool ok = true;
        if      (!strcmp(a, "--socket")  && more) path = argv[++i];
        else if (!strcmp(a, "--send")    && more) ok = add_send(argv[++i]);
        else if (!strcmp(a, "--ping"))            ok = add_send("{\"type\":\"ping\",\"id\":1}");
        else if (!strcmp(a, "--interval") && more) ok = parse_ul(argv[++i], &v) &&
                                                         add_fmt("{\"type\":\"set_interval\",\"interval_ms\":%lu}", v);
        else if (!strcmp(a, "--history") && more) {
            asked_history = true;
            ok = parse_ul(argv[++i], &v) &&
                 (v == 0 ? add_send("{\"type\":\"get_history\"}")
                         : add_fmt("{\"type\":\"get_history\",\"count\":%lu}", v));
        }
        else if (!strcmp(a, "--until-last"))      until_last = true;
        else if (!strcmp(a, "--long-line")) {
            char line[1600];
            strcpy(line, "{\"type\":\"ping\",\"pad\":\"");
            size_t n = strlen(line);
            memset(line + n, 'a', 1500);
            strcpy(line + n + 1500, "\"}");
            ok = add_send(line);
        }
        else if (!strcmp(a, "--count")   && more) { ok = parse_ul(argv[++i], &v); max_lines = (long)v; }
        else if (!strcmp(a, "--timeout") && more) timeout_s = atof(argv[++i]);
        else if (!strcmp(a, "--stall")   && more) stall_s = atof(argv[++i]);
        else if (!strcmp(a, "--brief"))           brief = true;
        else ok = false;
        if (!ok) { usage(); return 2; }
    }
    if (until_last && !asked_history) { fprintf(stderr, "monctl: --until-last needs --history\n"); usage(); return 2; }

    SetConsoleOutputCP(CP_UTF8);                  /* process names are UTF-8 */
    SetConsoleCtrlHandler(on_ctrl, TRUE);

    char defpath[108];
    if (!path) {
        if (sockpath_default(defpath, sizeof defpath, false) != 0) {
            fprintf(stderr, "monctl: cannot work out the default socket path; use --socket\n");
            return 2;
        }
        path = defpath;
    }
    if (strlen(path) >= sizeof((struct sockaddr_un *)0)->sun_path) {
        fprintf(stderr, "monctl: socket path is too long\n");
        return 2;
    }

    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) { fprintf(stderr, "monctl: WSAStartup failed\n"); return 1; }

    SOCKET sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock == INVALID_SOCKET) {
        fprintf(stderr, "monctl: cannot create an AF_UNIX socket (WSA %d)\n", WSAGetLastError());
        return 1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    strcpy(addr.sun_path, path);
    if (connect(sock, (struct sockaddr *)&addr, sizeof addr) == SOCKET_ERROR) {
        fprintf(stderr, "monctl: cannot connect to %s (WSA %d). Is monitord running?\n",
                path, WSAGetLastError());
        return 1;
    }

    for (int i = 0; i < g_nsend; i++) {
        char out[SEND_CAP + 2];
        int n = snprintf(out, sizeof out, "%s\n", g_sends[i]);
        for (int off = 0; off < n; ) {
            int w = send(sock, out + off, n - off, 0);
            if (w == SOCKET_ERROR) { fprintf(stderr, "monctl: send failed (WSA %d)\n", WSAGetLastError()); return 3; }
            off += w;
        }
    }

    if (stall_s > 0) {
        fprintf(stderr, "monctl: connected, not reading for %.0f s\n", stall_s);
        ULONGLONG until = GetTickCount64() + (ULONGLONG)(stall_s * 1000);
        while (!g_stop && GetTickCount64() < until) Sleep(100);
    }

    char *buf = malloc(BUF_SZ);
    if (!buf) return 1;
    size_t len = 0;
    stats_t st;
    memset(&st, 0, sizeof st);
    int rc = 0;
    ULONGLONG t0 = GetTickCount64();

    while (!g_stop) {
        if (timeout_s > 0 && (GetTickCount64() - t0) >= (ULONGLONG)(timeout_s * 1000)) break;

        fd_set rf;
        FD_ZERO(&rf);
        FD_SET(sock, &rf);
        struct timeval tv = { 0, 200 * 1000 };
        int r = select(0, &rf, NULL, NULL, &tv);
        if (r == SOCKET_ERROR) { fprintf(stderr, "monctl: select failed (WSA %d)\n", WSAGetLastError()); rc = 3; break; }
        if (r == 0) continue;

        int n = recv(sock, buf + len, (int)(BUF_SZ - len), 0);
        if (n == 0)            { fprintf(stderr, "monctl: the daemon closed the connection\n"); rc = 3; break; }
        if (n == SOCKET_ERROR) { fprintf(stderr, "monctl: receive failed (WSA %d)\n", WSAGetLastError()); rc = 3; break; }
        len += (size_t)n;

        size_t start = 0;
        bool done = false;
        for (size_t i = 0; i < len && !done; i++) {
            if (buf[i] != '\n') continue;
            buf[i] = '\0';
            bool final_chunk = process_line(buf + start, i - start, brief, &st);
            start = i + 1;
            st.lines++;
            if (max_lines && st.lines >= max_lines) done = true;
            if (until_last && final_chunk)          done = true;
        }
        if (done) break;
        if (start > 0) { memmove(buf, buf + start, len - start); len -= start; }
        if (len == BUF_SZ) { fprintf(stderr, "monctl: over-long line, discarding\n"); len = 0; }
    }

    print_summary(&st);
    free(buf);
    closesocket(sock);
    WSACleanup();
    return rc;
}