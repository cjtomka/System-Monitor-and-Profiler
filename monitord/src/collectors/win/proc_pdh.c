#include "proc_pdh.h"
#include "pdh_session.h"
#include "log.h"

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define TOP_BY_CPU 8
#define TOP_BY_MEM (MAX_TOP_PROCS - TOP_BY_CPU)      /* the rest of the slots */

typedef struct {
    const wchar_t *inst;    /* points into cpu_arr: valid only during one sample call */
    uint32_t       pid;
    double         cpu;     /* percent of the whole machine, 0..100 */
    uint64_t       rss;
    bool           picked;  /* already chosen in the by-CPU pass */
} cand_t;

typedef struct {
    PDH_HCOUNTER cpu;       /* \Process(*)\% Processor Time */
    PDH_HCOUNTER ws;        /* \Process(*)\Working Set */
    PDH_HCOUNTER pid;       /* \Process(*)\ID Process */
    pdh_array_t  cpu_arr, ws_arr, pid_arr;      /* reusable wildcard buffers */
    cand_t      *cand;                          /* reusable candidate list: grows, never shrinks */
    size_t       cand_cap;
    double       logical_cores;
} proc_ctx_t;

/* ---- small helpers ------------------------------------------------------ */

static double clamp_pct(double v) {
    if (!(v > 0.0)) return 0.0;             /* also catches NaN */
    return v > 100.0 ? 100.0 : v;
}

static uint64_t to_u64(double v) {
    if (!(v > 0.0)) return 0;
    return (uint64_t)v;
}

static int ensure_cand(proc_ctx_t *c, size_t need) {
    if (need <= c->cand_cap) return 0;
    size_t cap = c->cand_cap ? c->cand_cap : 256;
    while (cap < need) cap *= 2;
    cand_t *p = realloc(c->cand, cap * sizeof *p);
    if (!p) return -1;
    c->cand = p;
    c->cand_cap = cap;
    return 0;
}

/* Finds the instance called `name` in another counter's array. PDH normally
   returns every counter of one object in the same order, so index `hint`
   almost always hits; the linear scan is the fallback for when it does not. */
static const PDH_FMT_COUNTERVALUE_ITEM_W *match(const pdh_array_t *a, DWORD hint, const wchar_t *name) {
    if (hint < a->count && wcscmp(a->items[hint].szName, name) == 0)
        return pdh_cstatus_ok(a->items[hint].FmtValue.CStatus) ? &a->items[hint] : NULL;
    for (DWORD i = 0; i < a->count; i++) {
        if (wcscmp(a->items[i].szName, name) == 0)
            return pdh_cstatus_ok(a->items[i].FmtValue.CStatus) ? &a->items[i] : NULL;
    }
    return NULL;
}

/* Sort by CPU descending; ties go to the larger working set, then the lower
   PID, so the order is deterministic and an idle machine does not flicker. */
static int by_cpu_desc(const void *pa, const void *pb) {
    const cand_t *a = pa, *b = pb;
    if (a->cpu != b->cpu) return a->cpu < b->cpu ? 1 : -1;
    if (a->rss != b->rss) return a->rss < b->rss ? 1 : -1;
    return (a->pid > b->pid) - (a->pid < b->pid);
}

static int by_rss_desc(const void *pa, const void *pb) {
    const cand_t *a = pa, *b = pb;
    if (a->rss != b->rss) return a->rss < b->rss ? 1 : -1;
    return (a->pid > b->pid) - (a->pid < b->pid);
}

/* Real image name if the process can be opened (e.g. "chrome.exe"); otherwise
   the PDH instance name with its "#N" suffix removed. Output is UTF-8,
   truncated on a character boundary. */
static void resolve_name(uint32_t pid, const wchar_t *inst, char *dst, size_t dst_len) {
    wchar_t wname[260];
    wname[0] = L'\0';

    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (h) {
        wchar_t path[1024];
        DWORD len = (DWORD)(sizeof path / sizeof path[0]);
        if (QueryFullProcessImageNameW(h, 0, path, &len) && len > 0) {
            const wchar_t *base = wcsrchr(path, L'\\');
            base = base ? base + 1 : path;
            wcsncpy(wname, base, sizeof wname / sizeof wname[0] - 1);
            wname[sizeof wname / sizeof wname[0] - 1] = L'\0';
        }
        CloseHandle(h);
    }

    if (wname[0] == L'\0') {                /* protected process, or it already exited */
        wcsncpy(wname, inst, sizeof wname / sizeof wname[0] - 1);
        wname[sizeof wname / sizeof wname[0] - 1] = L'\0';
        wchar_t *hash = wcsrchr(wname, L'#');
        if (hash && hash[1] != L'\0') {
            bool digits = true;
            for (const wchar_t *p = hash + 1; *p; p++)
                if (*p < L'0' || *p > L'9') { digits = false; break; }
            if (digits) *hash = L'\0';
        }
    }

    char tmp[1024];                         /* 259 wide chars is at most 777 UTF-8 bytes */
    if (WideCharToMultiByte(CP_UTF8, 0, wname, -1, tmp, (int)sizeof tmp, NULL, NULL) <= 0) {
        dst[0] = '?'; dst[1] = '\0';
        return;
    }
    size_t n = strlen(tmp);
    if (n >= dst_len) {
        n = dst_len - 1;
        while (n > 0 && ((unsigned char)tmp[n] & 0xC0) == 0x80) n--;   /* do not split a character */
    }
    memcpy(dst, tmp, n);
    dst[n] = '\0';
}

static void emit(proc_stats_t *p, const cand_t *c) {
    p->pid       = c->pid;
    p->cpu_pct   = (float)c->cpu;
    p->rss_bytes = c->rss;
    resolve_name(c->pid, c->inst, p->name, sizeof p->name);
}

/* ---- collector interface ------------------------------------------------ */

int proc_init(void **ctx) {
    proc_ctx_t *c = calloc(1, sizeof *c);
    if (!c) return -1;

    DWORD cores = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    c->logical_cores = cores ? (double)cores : 1.0;

    if (pdh_session_open() != 0) { free(c); return -1; }

    if (pdh_session_add_counter(L"\\Process(*)\\% Processor Time", &c->cpu) != 0 ||
        pdh_session_add_counter(L"\\Process(*)\\Working Set",      &c->ws)  != 0 ||
        pdh_session_add_counter(L"\\Process(*)\\ID Process",       &c->pid) != 0) {
        log_error("process collector: Process counters unavailable "
                  "(try 'lodctr /R' in an elevated prompt)");
        pdh_session_close();
        free(c);
        return -1;
    }
    *ctx = c;
    return 0;
}

int proc_sample(void *vctx, snapshot_t *out) {
    proc_ctx_t *c = vctx;

    switch (pdh_session_state()) {
    case PDH_SESSION_WARMING: return 0;     /* CPU is a rate counter: no value until collection 2 */
    case PDH_SESSION_FAILED:  return -1;
    case PDH_SESSION_READY:   break;
    }

    if (pdh_get_double_array(c->cpu, &c->cpu_arr) != 0 ||
        pdh_get_double_array(c->ws,  &c->ws_arr)  != 0 ||
        pdh_get_double_array(c->pid, &c->pid_arr) != 0)
        return -1;

    if (ensure_cand(c, c->cpu_arr.count) != 0) return -1;

    /* 1. Join the three arrays into one candidate list, matching by instance name. */
    size_t nc = 0;
    for (DWORD i = 0; i < c->cpu_arr.count; i++) {
        const PDH_FMT_COUNTERVALUE_ITEM_W *ci = &c->cpu_arr.items[i];
        const wchar_t *name = ci->szName;

        if (wcscmp(name, L"_Total") == 0) continue;
        if (!pdh_cstatus_ok(ci->FmtValue.CStatus)) continue;   /* e.g. a process that just appeared */

        const PDH_FMT_COUNTERVALUE_ITEM_W *wi = match(&c->ws_arr,  i, name);
        const PDH_FMT_COUNTERVALUE_ITEM_W *pi = match(&c->pid_arr, i, name);
        if (!wi || !pi) continue;

        uint32_t pid = (uint32_t)pi->FmtValue.doubleValue;
        if (pid == 0) continue;                                /* System Idle Process */

        cand_t *k = &c->cand[nc++];
        k->inst   = name;
        k->pid    = pid;
        k->cpu    = clamp_pct(ci->FmtValue.doubleValue / c->logical_cores);
        k->rss    = to_u64(wi->FmtValue.doubleValue);
        k->picked = false;
    }

    /* 2. Top by CPU, then top by memory among the ones not already taken. */
    unsigned n = 0;

    qsort(c->cand, nc, sizeof *c->cand, by_cpu_desc);
    for (size_t i = 0; i < nc && n < TOP_BY_CPU; i++) {
        c->cand[i].picked = true;
        emit(&out->procs[n++], &c->cand[i]);
    }

    qsort(c->cand, nc, sizeof *c->cand, by_rss_desc);
    for (size_t i = 0; i < nc && n < TOP_BY_CPU + TOP_BY_MEM; i++) {
        if (c->cand[i].picked) continue;
        emit(&out->procs[n++], &c->cand[i]);
    }

    out->proc_count  = (uint8_t)n;
    out->procs_valid = true;
    return 0;
}

void proc_shutdown(void *vctx) {
    proc_ctx_t *c = vctx;
    if (!c) return;
    pdh_array_free(&c->cpu_arr);
    pdh_array_free(&c->ws_arr);
    pdh_array_free(&c->pid_arr);
    free(c->cand);
    free(c);
    pdh_session_close();
}