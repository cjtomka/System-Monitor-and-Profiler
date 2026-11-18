#include "sampler.h"
#include "snapshot.h"
#include "log.h"          /* assumes log_info / log_warn(const char *fmt, ...) */
#include <windows.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#define MIN_INTERVAL_MS   100
#define MAX_INTERVAL_MS 10000

struct sampler {
    sampler_config_t cfg;
    void           **ctx;          /* per-collector private state */
    bool            *enabled;      /* false if init failed: skipped every cycle */
    bool            *failed;       /* true while a collector is failing (log once) */
    HANDLE           thread;       /* NULL when not running */
    HANDLE           timer;
    HANDLE           stop_evt;
    volatile LONG    interval_ms;  /* read by the thread, written by any thread */
    uint64_t         seq;          /* touched only by the sampler thread */
    LARGE_INTEGER    qpf;
};

static uint32_t clamp_interval(uint32_t ms) {
    if (ms < MIN_INTERVAL_MS) return MIN_INTERVAL_MS;
    if (ms > MAX_INTERVAL_MS) return MAX_INTERVAL_MS;
    return ms;
}

static uint64_t wall_clock_ms(void) {
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    ULARGE_INTEGER u = { .LowPart = ft.dwLowDateTime, .HighPart = ft.dwHighDateTime };
    return (u.QuadPart - 116444736000000000ULL) / 10000ULL;   /* 1601 -> 1970 */
}

/* ---- the sampling thread ------------------------------------------------ */

static DWORD WINAPI sampler_main(LPVOID arg) {
    sampler_t *s = arg;
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    int64_t next = now.QuadPart;              /* first sample fires immediately */
    bool warming = true;

    for (;;) {
        if (WaitForSingleObject(s->stop_evt, 0) == WAIT_OBJECT_0) break;

        /* 1. sleep until the absolute deadline, or wake early on stop */
        QueryPerformanceCounter(&now);
        int64_t remain = next - now.QuadPart;
        if (remain > 0) {
            LARGE_INTEGER due;                /* relative, 100 ns units, negative */
            due.QuadPart = -(remain * 10000000LL / s->qpf.QuadPart);
            SetWaitableTimer(s->timer, &due, 0, NULL, NULL, FALSE);
            HANDLE h[2] = { s->stop_evt, s->timer };
            if (WaitForMultipleObjects(2, h, FALSE, INFINITE) == WAIT_OBJECT_0)
                break;
        }

        /* 2. sample */
        snapshot_t snap;
        memset(&snap, 0, sizeof snap);        /* every section starts invalid */
        snap.seq        = ++s->seq;
        snap.ts_ms      = wall_clock_ms();
        snap.warming_up = warming;

        if (s->cfg.begin_cycle) s->cfg.begin_cycle();

        for (size_t i = 0; i < s->cfg.n_collectors; i++) {
            if (!s->enabled[i]) continue;
            const collector_t *c = &s->cfg.collectors[i];
            int rc = c->sample(s->ctx[i], &snap);
            if (rc != 0 && !s->failed[i]) {
                log_warn("collector '%s' failed (rc=%d)", c->name, rc);
                s->failed[i] = true;          /* section stays invalid -> null */
            } else if (rc == 0 && s->failed[i]) {
                log_info("collector '%s' recovered", c->name);
                s->failed[i] = false;
            }
        }

        ringbuf_push(s->cfg.ring, &snap);
        warming = false;

        /* 3. next deadline comes from the previous one, not from "now" */
        LONG ms = InterlockedCompareExchange(&s->interval_ms, 0, 0);   /* atomic read */
        int64_t interval = (int64_t)ms * s->qpf.QuadPart / 1000;
        next += interval;
        QueryPerformanceCounter(&now);
        if (now.QuadPart > next)              /* overran: resync, don't burst */
            next = now.QuadPart + interval;
    }
    return 0;
}

/* ---- lifecycle ---------------------------------------------------------- */

sampler_t *sampler_create(const sampler_config_t *cfg) {
    if (!cfg || !cfg->collectors || cfg->n_collectors == 0 || !cfg->ring)
        return NULL;

    sampler_t *s = calloc(1, sizeof *s);
    if (!s) return NULL;

    size_t n = cfg->n_collectors;
    s->cfg         = *cfg;
    s->cfg.interval_ms = clamp_interval(cfg->interval_ms);
    s->interval_ms = (LONG)s->cfg.interval_ms;
    QueryPerformanceFrequency(&s->qpf);

    s->ctx      = calloc(n, sizeof *s->ctx);
    s->enabled  = calloc(n, sizeof *s->enabled);
    s->failed   = calloc(n, sizeof *s->failed);
    s->stop_evt = CreateEventW(NULL, TRUE, FALSE, NULL);      /* manual-reset */
    s->timer    = CreateWaitableTimerExW(NULL, NULL,
                      CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
    if (!s->timer)                                            /* pre-1803 fallback */
        s->timer = CreateWaitableTimerW(NULL, FALSE, NULL);

    if (!s->ctx || !s->enabled || !s->failed || !s->stop_evt || !s->timer) {
        sampler_destroy(s);
        return NULL;
    }

    /* A collector whose init fails is disabled, not fatal: the daemon keeps
       running and that section is reported as null. */
    for (size_t i = 0; i < n; i++) {
        const collector_t *c = &cfg->collectors[i];
        if (c->init(&s->ctx[i]) == 0) {
            s->enabled[i] = true;
        } else {
            log_warn("collector '%s' init failed; disabled", c->name);
        }
    }
    return s;
}

int sampler_start(sampler_t *s) {
    if (!s || s->thread) return -1;                           /* already running */
    ResetEvent(s->stop_evt);
    s->thread = CreateThread(NULL, 0, sampler_main, s, 0, NULL);
    return s->thread ? 0 : -1;
}

void sampler_set_interval(sampler_t *s, uint32_t ms) {
    if (!s) return;
    InterlockedExchange(&s->interval_ms, (LONG)clamp_interval(ms));
}

uint32_t sampler_get_interval(sampler_t *s) {
    return s ? (uint32_t)InterlockedCompareExchange(&s->interval_ms, 0, 0) : 0;
}

void sampler_stop(sampler_t *s) {
    if (!s || !s->thread) return;
    SetEvent(s->stop_evt);
    WaitForSingleObject(s->thread, INFINITE);
    CloseHandle(s->thread);
    s->thread = NULL;
}

void sampler_destroy(sampler_t *s) {
    if (!s) return;
    sampler_stop(s);                                          /* thread gone before teardown */

    if (s->enabled && s->ctx) {
        for (size_t i = 0; i < s->cfg.n_collectors; i++)
            if (s->enabled[i])
                s->cfg.collectors[i].shutdown(s->ctx[i]);
    }
    if (s->timer)    CloseHandle(s->timer);
    if (s->stop_evt) CloseHandle(s->stop_evt);
    free(s->ctx);
    free(s->enabled);
    free(s->failed);
    free(s);
}