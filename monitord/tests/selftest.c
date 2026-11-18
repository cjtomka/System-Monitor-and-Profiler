/* Stage-1 harness: sampler + ring buffer + logger, driven by mock collectors. */
#include <windows.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <ctype.h> //included with disk_pdh
#include <wctype.h> //included with disk_pdh
#include <psapi.h> //for GetProcessMemoryInfo cross-check in verify()

#include "collector.h"
#include "log.h"
#include "ringbuf.h"
#include "sampler.h"
#include "snapshot.h"
#include "collectors/win/pdh_session.h"
#include "collectors/win/cpu_pdh.h"
#include "collectors/win/mem_pdh.h"
#include "collectors/win/disk_pdh.h"
#include "collectors/win/proc_pdh.h"

/* ---- tiny check helper -------------------------------------------------- */

static int g_fail = 0;
#define CHECK(cond, ...) do {                         \
        if (cond) printf("  PASS  ");                 \
        else    { printf("  FAIL  "); g_fail++; }     \
        printf(__VA_ARGS__); printf("\n");            \
    } while (0)

/* ---- mock collectors ---------------------------------------------------- */

/*typedef struct { unsigned calls; } mock_ctx_t;

static int  mock_init(void **ctx)  { *ctx = calloc(1, sizeof(mock_ctx_t)); return *ctx ? 0 : -1; }
static void mock_shutdown(void *c) { free(c); }*/

/* Fills the CPU section with a predictable sawtooth: total_pct = ((seq-1)*10) % 100.
   Sleeps 5 ms to stand in for real work. 
static int mock_cpu_sample(void *vctx, snapshot_t *out) {
    mock_ctx_t *c = vctx;
    unsigned n = c->calls++;                          0-based: equals seq - 1 
    out->cpu.valid      = true;
    out->cpu.core_count = 4;
    out->cpu.total_pct  = (double)((n * 10u) % 100u);
    for (unsigned i = 0; i < 4; i++)
        out->cpu.cores_pct[i] = (float)((n * 10u + i * 5u) % 100u);
    Sleep(5);
    return 0;
} */

/* Fills the memory section but FAILS on cycles 5..7, to exercise the
   "section stays invalid, other collectors unaffected, log once" path. */
/*static int mock_flaky_sample(void *vctx, snapshot_t *out) {
    mock_ctx_t *c = vctx;
    unsigned cycle = ++c->calls;                      1-based: equals seq 
    if (cycle >= 5 && cycle <= 7) return -1;
    out->procs_valid = true;
    out->proc_count  = 0;
    return 0;
}
*/

static const collector_t g_collectors[] = {
    { "cpu",   cpu_init, cpu_sample,   cpu_shutdown },
    { "mem",   mem_init, mem_sample,   mem_shutdown },
    { "disk", disk_init, disk_sample,  disk_shutdown},
    { "proc", proc_init, proc_sample, proc_shutdown },
};

/* ---- Ctrl+C: set a flag, nothing else ----------------------------------- */

static volatile LONG g_stop = 0;

static BOOL WINAPI on_ctrl(DWORD type) {
    if (type == CTRL_C_EVENT || type == CTRL_BREAK_EVENT || type == CTRL_CLOSE_EVENT) {
        InterlockedExchange(&g_stop, 1);
        return TRUE;
    }
    return FALSE;
}

/* ---- options ------------------------------------------------------------ */

typedef struct {
    uint32_t    interval_ms;
    uint32_t    duration_s;     /* 0 = run until Ctrl+C */
    bool        retune;         /* halve the interval at the midpoint */
    bool        quiet;
    log_level_t log_level;
    const char *log_file;
} options_t;

static bool parse_args(int argc, char **argv, options_t *o) {
    o->interval_ms = 1000; o->duration_s = 0; o->retune = false;
    o->quiet = false; o->log_level = LOG_INFO; o->log_file = NULL;

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        bool has_val = (i + 1 < argc);
        if      (!strcmp(a, "--interval-ms") && has_val) o->interval_ms = (uint32_t)strtoul(argv[++i], NULL, 10);
        else if (!strcmp(a, "--duration-s")  && has_val) o->duration_s  = (uint32_t)strtoul(argv[++i], NULL, 10);
        else if (!strcmp(a, "--log-file")    && has_val) o->log_file    = argv[++i];
        else if (!strcmp(a, "--log-level")   && has_val) {
            if (!log_level_parse(argv[++i], &o->log_level)) { fprintf(stderr, "bad --log-level\n"); return false; }
        }
        else if (!strcmp(a, "--retune")) o->retune = true;
        else if (!strcmp(a, "--quiet"))  o->quiet  = true;
        else {
            fprintf(stderr,
                "usage: monitord [--interval-ms N] [--duration-s N] [--retune] [--quiet]\n"
                "                [--log-level debug|info|warn|error] [--log-file PATH]\n");
            return false;
        }
    }
    if (o->interval_ms < 100)   o->interval_ms = 100;      /* same clamp as the sampler */
    if (o->interval_ms > 10000) o->interval_ms = 10000;
    if (o->retune && o->duration_s == 0) { fprintf(stderr, "--retune needs --duration-s\n"); return false; }
    return true;
}

/* ---- self-check --------------------------------------------------------- */

#define TOL_MS 25

#define TOL_MS 25

static void verify(ringbuf_t *ring, size_t cap, uint32_t interval_ms,
                   uint32_t retuned_ms, uint64_t change_seq, DWORD stop_ms) {
    snapshot_t *h = malloc(cap * sizeof *h);               /* too big for the stack */
    if (!h) { printf("verify: out of memory\n"); g_fail++; return; }
    size_t n = ringbuf_copy_last(ring, cap, h);

    printf("\nself-check (%zu snapshots)\n", n);
    CHECK(n >= 2, "collected at least 2 snapshots (got %zu)", n);
    if (n < 2) { free(h); return; }

    bool contiguous = true, warm_ok = true;
    bool cpu_ok = true, core_avg_ok = true, mem_ok = true, disk_ok = true, proc_ok = true;
    unsigned want_cores = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    MEMORYSTATUSEX ms = { .dwLength = sizeof ms };
    GlobalMemoryStatusEx(&ms);

    wchar_t wd[MAX_PATH] = L"C";
    GetWindowsDirectoryW(wd, MAX_PATH);
    char sys_letter = (char)towupper(wd[0]);               /* the drive Windows lives on */

    for (size_t i = 0; i < n; i++) {
        const snapshot_t *s = &h[i];
        if (i > 0 && s->seq != h[i - 1].seq + 1) contiguous = false;

        if (s->warming_up) {
            /* Rate-based sections have nothing to report yet. */
            if (s->cpu.valid)   cpu_ok  = false;
            if (s->disks_valid) disk_ok = false;
            if (s->procs_valid) proc_ok = false;
        } else {
            /* CPU */
            if (!s->cpu.valid || s->cpu.total_pct < 0.0 || s->cpu.total_pct > 100.0)
                cpu_ok = false;
            if (want_cores <= MAX_CORES && s->cpu.core_count != want_cores)
                cpu_ok = false;

            double sum = 0;
            for (unsigned k = 0; k < s->cpu.core_count; k++) sum += s->cpu.cores_pct[k];
            if (s->cpu.core_count &&
                fabs(sum / s->cpu.core_count - s->cpu.total_pct) > 5.0)
                core_avg_ok = false;

            /* Memory */
            const mem_stats_t *m = &s->mem;
            if (!m->valid ||
                m->total_bytes != ms.ullTotalPhys ||
                m->avail_bytes == 0 || m->avail_bytes > m->total_bytes ||
                m->commit_limit_bytes == 0 || m->commit_bytes > m->commit_limit_bytes)
                mem_ok = false;

            /* Disks: at least one, sane numbers, and the system drive is present. */
            if (!s->disks_valid || s->disk_count == 0) {
                disk_ok = false;
            } else {
                bool sys_seen = false;
                for (unsigned k = 0; k < s->disk_count; k++) {
                    const disk_stats_t *d = &s->disks[k];
                    if (d->name[1] != ':' || d->total_bytes == 0 ||
                        d->free_bytes > d->total_bytes || d->queue < 0.0)
                        disk_ok = false;
                    if (toupper((unsigned char)d->name[0]) == sys_letter) sys_seen = true;
                }
                if (!sys_seen) disk_ok = false;
            }

            /* Processes: a non-empty list within the cap, sane fields, no duplicate PIDs. */
            if (!s->procs_valid || s->proc_count == 0 || s->proc_count > MAX_TOP_PROCS) {
                proc_ok = false;
            } else {
                for (unsigned a = 0; a < s->proc_count; a++) {
                    const proc_stats_t *p = &s->procs[a];
                    if (p->pid == 0 || p->name[0] == '\0' ||
                        p->cpu_pct < 0.0f || p->cpu_pct > 100.0f)
                        proc_ok = false;
                    for (unsigned b = a + 1; b < s->proc_count; b++)
                        if (s->procs[b].pid == p->pid) proc_ok = false;
                }
            }
        }

        if (s->warming_up != (s->seq == 1)) warm_ok = false;
    }

    CHECK(contiguous, "seq values are contiguous (%llu..%llu)",
          (unsigned long long)h[0].seq, (unsigned long long)h[n - 1].seq);
    CHECK(cpu_ok, "CPU: invalid while warming up, 0-100%% after, core count = %u", want_cores);
    CHECK(core_avg_ok, "mean of per-core values matches the total (within 5 points)");
    CHECK(mem_ok, "memory: valid every cycle, total matches the OS, avail <= total, commit <= limit");
    CHECK(disk_ok, "disks: invalid while warming up, then drive %c: present with sane sizes", sys_letter);
    CHECK(proc_ok, "processes: invalid while warming up, then 1..%d entries, sane fields, unique PIDs",
          MAX_TOP_PROCS);
    if (h[0].seq == 1)                                      /* skip if the ring wrapped */
        CHECK(warm_ok, "only the first snapshot is flagged warming_up");

    /* Independent cross-check: the last PDH memory reading vs. a fresh OS query. */
    MEMORYSTATUSEX now = { .dwLength = sizeof now };
    if (GlobalMemoryStatusEx(&now) && h[n - 1].mem.valid) {
        int64_t diff = (int64_t)h[n - 1].mem.avail_bytes - (int64_t)now.ullAvailPhys;
        if (diff < 0) diff = -diff;
        CHECK((uint64_t)diff <= ms.ullTotalPhys / 20,
              "available memory agrees with GlobalMemoryStatusEx (off by %llu MiB)",
              (unsigned long long)(diff >> 20));
    }

    /* Independent cross-check of the PID <-> working-set pairing. If the three
       process counters had been matched wrongly, working sets would land on the
       wrong PIDs and these would disagree wildly. Processes we cannot open
       (protected, or already exited) are skipped. */
    const snapshot_t *last = &h[n - 1];
    unsigned compared = 0;
    bool ws_ok = true;
    if (last->procs_valid) {
        for (unsigned k = 0; k < last->proc_count; k++) {
            const proc_stats_t *p = &last->procs[k];
            HANDLE hp = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, p->pid);
            if (!hp) continue;
            PROCESS_MEMORY_COUNTERS pmc = { .cb = sizeof pmc };
            if (GetProcessMemoryInfo(hp, &pmc, sizeof pmc)) {
                double a = (double)p->rss_bytes, b = (double)pmc.WorkingSetSize;
                double tol = fmax(64.0 * 1048576.0, 0.25 * fmax(a, b));   /* working sets move quickly */
                if (fabs(a - b) > tol) ws_ok = false;
                compared++;
            }
            CloseHandle(hp);
        }
    }
    CHECK(compared > 0 && ws_ok,
          "process working sets agree with GetProcessMemoryInfo (%u processes compared)", compared);

    int64_t worst = 0;
    for (size_t i = 1; i < n; i++) {
        uint64_t seq = h[i].seq;
        int64_t expect;
        if (change_seq == 0 || seq <= change_seq) expect = interval_ms;
        else if (seq >= change_seq + 3)           expect = retuned_ms;
        else continue;                       /* ambiguous window around the change */
        int64_t err = (int64_t)(h[i].ts_ms - h[i - 1].ts_ms) - expect;
        if (err < 0) err = -err;
        if (err > worst) worst = err;
    }
    CHECK(worst <= TOL_MS, "spacing within %d ms of target (worst %lld ms)", TOL_MS, (long long)worst);

    if (change_seq == 0) {
        int64_t span  = (int64_t)(h[n - 1].ts_ms - h[0].ts_ms);
        int64_t drift = span - (int64_t)(n - 1) * interval_ms;
        if (drift < 0) drift = -drift;
        CHECK(drift <= TOL_MS, "no cumulative drift over %zu intervals (off by %lld ms)",
              n - 1, (long long)drift);
    }
    CHECK(stop_ms < 250, "sampler_stop returned in %lu ms", (unsigned long)stop_ms);

    free(h);
}

static void print_snapshot(const snapshot_t *s) {
    char memtxt[48] = "null";
    if (s->mem.valid)
        snprintf(memtxt, sizeof memtxt, "%.1f/%.1f GiB free",
                 s->mem.avail_bytes / 1073741824.0,
                 s->mem.total_bytes / 1073741824.0);

    char disktxt[256] = "null";
    if (s->disks_valid) {
        size_t off = 0;
        disktxt[0] = '\0';
        if (s->disk_count == 0) snprintf(disktxt, sizeof disktxt, "none");
        for (unsigned k = 0; k < s->disk_count; k++) {
            const disk_stats_t *d = &s->disks[k];
            int w = snprintf(disktxt + off, sizeof disktxt - off,
                             "%s%s %.0f GiB free r=%.1f w=%.1f MB/s q=%.2f",
                             k ? " | " : "", d->name,
                             d->free_bytes / 1073741824.0,
                             d->read_bps / 1048576.0, d->write_bps / 1048576.0,
                             d->queue);
            if (w < 0 || (size_t)w >= sizeof disktxt - off) break;   /* line full */
            off += (size_t)w;
        }
    }

    /* The first entries are the by-CPU picks, highest first, so these are the top 3. */
    char proctxt[256] = "null";
    if (s->procs_valid) {
        int w = snprintf(proctxt, sizeof proctxt, "%u listed", s->proc_count);
        size_t off = w > 0 ? (size_t)w : 0;
        for (unsigned k = 0; k < s->proc_count && k < 3; k++) {
            const proc_stats_t *p = &s->procs[k];
            w = snprintf(proctxt + off, sizeof proctxt - off, "%s%s %.1f%% %.0f MB",
                         k ? ", " : ": ", p->name, p->cpu_pct, p->rss_bytes / 1048576.0);
            if (w < 0 || (size_t)w >= sizeof proctxt - off) break;
            off += (size_t)w;
        }
    }

    printf("seq=%-4llu ts=%llu warm=%d cpu=%5.1f%% cores=%u mem=%s\n"
           "         disks: %s\n"
           "         procs: %s\n",
           (unsigned long long)s->seq, (unsigned long long)s->ts_ms,
           s->warming_up, s->cpu.valid ? s->cpu.total_pct : -1.0,
           s->cpu.core_count, memtxt, disktxt, proctxt);
}
/* ---- main --------------------------------------------------------------- */

int main(int argc, char **argv) {
    options_t o;
    if (!parse_args(argc, argv, &o)) return 2;

    log_init(o.log_level, o.log_file);
    SetConsoleCtrlHandler(on_ctrl, TRUE);

    uint32_t retuned_ms = o.interval_ms / 2 < 100 ? 100 : o.interval_ms / 2;
    uint32_t min_iv     = o.retune ? retuned_ms : o.interval_ms;
    size_t   cap        = o.duration_s ? (size_t)o.duration_s * 1000 / min_iv + 32 : 300;

    log_info("harness starting: interval=%u ms, duration=%u s, ring=%zu",
             o.interval_ms, o.duration_s, cap);

    ringbuf_t *ring = ringbuf_create(cap);
    if (!ring) { log_error("ringbuf_create failed"); return 1; }

    sampler_config_t cfg = {
        .collectors   = g_collectors,
        .n_collectors = sizeof g_collectors / sizeof g_collectors[0],
        .begin_cycle  = pdh_collect,                 /* becomes pdh_collect later */
        .ring         = ring,
        .interval_ms  = o.interval_ms,
    };
    sampler_t *s = sampler_create(&cfg);
    if (!s || sampler_start(s) != 0) { log_error("sampler failed to start"); return 1; }

    /* Same pattern the server thread will use: poll the sequence number, copy on change. */
    uint64_t last_seq = 0, change_seq = 0;
    bool retuned = false;
    ULONGLONG t0 = GetTickCount64();

    while (!g_stop) {
        ULONGLONG elapsed = GetTickCount64() - t0;
        if (o.duration_s && elapsed >= (ULONGLONG)o.duration_s * 1000) break;

        if (o.retune && !retuned && elapsed >= (ULONGLONG)o.duration_s * 500) {
            uint64_t k = 0;
            ringbuf_latest_seq(ring, &k);
            change_seq = k ? k : 1;           /* must be nonzero: 0 means "never retuned" */
            sampler_set_interval(s, retuned_ms);
            retuned = true;
            log_info("retuned %u -> %u ms after seq %llu",
                     o.interval_ms, retuned_ms, (unsigned long long)change_seq);
        }

        uint64_t seq;
        if (ringbuf_latest_seq(ring, &seq) && seq != last_seq) {
            snapshot_t snap;
            if (ringbuf_latest(ring, &snap)) {
                if (!o.quiet) print_snapshot(&snap);
                last_seq = snap.seq;          /* the line that must not go missing */
            }
        }
        Sleep(50);
    }

    DWORD t_stop = GetTickCount();
    sampler_stop(s);
    DWORD stop_ms = GetTickCount() - t_stop;
    sampler_destroy(s);

    verify(ring, cap, o.interval_ms, retuned_ms, change_seq, stop_ms);

    ringbuf_destroy(ring);
    log_info("harness done: %s", g_fail ? "FAILED" : "all checks passed");
    log_shutdown();
    return g_fail ? 1 : 0;
}