#include <windows.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "collector.h"
#include "log.h"
#include "ringbuf.h"
#include "sampler.h"
#include "server.h"
#include "collectors/win/pdh_session.h"
#include "collectors/win/cpu_pdh.h"
#include "collectors/win/mem_pdh.h"
#include "collectors/win/disk_pdh.h"
#include "collectors/win/proc_pdh.h"

#define DAEMON_VERSION "monitord 0.1.0"

static const collector_t g_collectors[] = {
    { "cpu",  cpu_init,  cpu_sample,  cpu_shutdown },
    { "mem",  mem_init,  mem_sample,  mem_shutdown },
    { "disk", disk_init, disk_sample, disk_shutdown },
    { "proc", proc_init, proc_sample, proc_shutdown },
};

/* Set by Ctrl+C, a closed console window, or a closed standard input. The main thread polls it. */
static volatile LONG g_stop = 0;

/* Ctrl+C handler: set a flag, nothing else. A closed console window gives us only a few seconds,
   so a very fast exit can leave the socket file behind. That is fine: the next start detects and
   removes a stale file. */
static BOOL WINAPI on_ctrl(DWORD type) {
    if (type == CTRL_C_EVENT || type == CTRL_BREAK_EVENT || type == CTRL_CLOSE_EVENT) {
        InterlockedExchange(&g_stop, 1);
        return TRUE;
    }
    return FALSE;
}

/* With --exit-on-stdin-eof the daemon shuts down cleanly when its standard input is closed. The
   launch script holds the other end of a pipe, so if the script ends (or is killed) the daemon
   goes with it instead of lingering and holding the socket. */
static DWORD WINAPI watch_stdin(LPVOID unused) {
    (void)unused;
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    char buf[64];
    DWORD n = 0;
    while (ReadFile(in, buf, sizeof buf, &n, NULL) && n > 0) {
        /* discard anything written to us; only the end of the stream matters */
    }
    log_info("standard input closed; shutting down");
    InterlockedExchange(&g_stop, 1);
    return 0;
}

typedef struct {
    uint32_t    interval_ms;        /* the sampler clamps this to 100..10000 */
    uint32_t    history;            /* ring size, in snapshots */
    uint32_t    queue_kb;           /* per-client send queue; 0 = server default */
    const char *socket_path;
    log_level_t log_level;
    const char *log_file;
    bool        exit_on_stdin_eof;
} options_t;

static bool parse_u32(const char *s, uint32_t *out) {
    if (!s || !*s || *s == '-') return false;
    char *end;
    unsigned long v = strtoul(s, &end, 10);
    if (*end != '\0') return false;
    *out = (uint32_t)v;
    return true;
}

static void usage(void) {
    fprintf(stderr,
        "usage: monitord [--interval-ms N] [--history N] [--socket PATH]\n"
        "                [--client-queue-kb N] [--log-level debug|info|warn|error] [--log-file PATH]\n"
        "                [--exit-on-stdin-eof]\n");
}

static bool parse_args(int argc, char **argv, options_t *o) {
    o->interval_ms = 1000; o->history = 600; o->queue_kb = 0;
    o->socket_path = NULL; o->log_level = LOG_INFO; o->log_file = NULL;
    o->exit_on_stdin_eof = false;

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        bool more = (i + 1 < argc);
        if      (!strcmp(a, "--interval-ms")     && more) { if (!parse_u32(argv[++i], &o->interval_ms)) { usage(); return false; } }
        else if (!strcmp(a, "--history")         && more) { if (!parse_u32(argv[++i], &o->history))     { usage(); return false; } }
        else if (!strcmp(a, "--client-queue-kb") && more) { if (!parse_u32(argv[++i], &o->queue_kb))    { usage(); return false; } }
        else if (!strcmp(a, "--socket")          && more) o->socket_path = argv[++i];
        else if (!strcmp(a, "--log-file")        && more) o->log_file = argv[++i];
        else if (!strcmp(a, "--log-level")       && more) {
            if (!log_level_parse(argv[++i], &o->log_level)) { fprintf(stderr, "bad --log-level\n"); return false; }
        }
        else if (!strcmp(a, "--exit-on-stdin-eof")) o->exit_on_stdin_eof = true;
        else { usage(); return false; }
    }
    if (o->history < 1)      o->history = 1;
    if (o->history > 100000) o->history = 100000;
    return true;
}

int main(int argc, char **argv) {
    options_t o;
    if (!parse_args(argc, argv, &o)) return 2;

    log_init(o.log_level, o.log_file);
    SetConsoleCtrlHandler(on_ctrl, TRUE);
    log_info(DAEMON_VERSION " starting: interval=%u ms, history=%u snapshots", o.interval_ms, o.history);

    int rc = 1;
    ringbuf_t *ring = ringbuf_create(o.history);
    sampler_t *sampler = NULL;
    server_t  *server = NULL;
    if (!ring) { log_error("cannot allocate the history buffer"); goto done; }

    sampler_config_t scfg = {
        .collectors   = g_collectors,
        .n_collectors = sizeof g_collectors / sizeof g_collectors[0],
        .begin_cycle  = pdh_collect,
        .ring         = ring,
        .interval_ms  = o.interval_ms,
    };
    sampler = sampler_create(&scfg);
    if (!sampler) { log_error("cannot create the sampler"); goto done; }

    server_config_t vcfg = {
        .socket_path        = o.socket_path,
        .ring               = ring,
        .sampler            = sampler,
        .daemon_version     = DAEMON_VERSION,
        .cores              = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS),
        .history_capacity   = o.history,
        .client_queue_bytes = (size_t)o.queue_kb * 1024,
    };
    server = server_create(&vcfg);
    if (!server) goto done;                       /* the reason has been logged */

    if (sampler_start(sampler) != 0 || server_start(server) != 0) {
        log_error("cannot start the sampler or server thread");
        goto done;
    }

    if (o.exit_on_stdin_eof) {
        HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
        if (in == NULL || in == INVALID_HANDLE_VALUE) {
            log_warn("--exit-on-stdin-eof: there is no standard input; ignoring the option");
        } else {
            HANDLE t = CreateThread(NULL, 0, watch_stdin, NULL, 0, NULL);
            if (t) CloseHandle(t);
        }
    }

    log_info("listening on %s (Ctrl+C to stop)", server_socket_path(server));

    while (!g_stop) Sleep(100);
    log_info("shutting down");
    rc = 0;

done:
    server_stop(server);          /* stop the readers first, then what they read from */
    server_destroy(server);
    sampler_stop(sampler);
    sampler_destroy(sampler);
    ringbuf_destroy(ring);
    log_shutdown();
    return rc;
}
