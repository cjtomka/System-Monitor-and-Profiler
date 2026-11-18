#include "cpu_pdh.h"
#include "pdh_session.h"
#include "log.h"

#include <stdlib.h>
#include <wchar.h>

typedef struct {
    PDH_HCOUNTER total;     /* \Processor(_Total)\% Processor Time */
    PDH_HCOUNTER cores;     /* \Processor(*)\% Processor Time, one instance per core */
    pdh_array_t  arr;       /* reusable buffer for the wildcard read */
} cpu_ctx_t;

static double clamp_pct(double v) {
    if (v < 0.0)   return 0.0;
    if (v > 100.0) return 100.0;
    return v;
}

int cpu_init(void **ctx) {
    cpu_ctx_t *c = calloc(1, sizeof *c);
    if (!c) return -1;

    if (pdh_session_open() != 0) { free(c); return -1; }

    if (pdh_session_add_counter(L"\\Processor(_Total)\\% Processor Time", &c->total) != 0 ||
        pdh_session_add_counter(L"\\Processor(*)\\% Processor Time",      &c->cores) != 0) {
        log_error("cpu collector: if counters are missing or corrupted, "
                  "run 'lodctr /R' in an elevated prompt");
        pdh_session_close();
        free(c);
        return -1;
    }
    *ctx = c;
    return 0;
}

int cpu_sample(void *vctx, snapshot_t *out) {
    cpu_ctx_t *c = vctx;

    switch (pdh_session_state()) {
    case PDH_SESSION_WARMING: return 0;     /* expected on cycle 1: leave the section invalid, no error */
    case PDH_SESSION_FAILED:  return -1;
    case PDH_SESSION_READY:   break;
    }

    double total;
    if (pdh_get_double(c->total, &total) != 0) return -1;

    cpu_stats_t *cpu = &out->cpu;
    cpu->total_pct  = clamp_pct(total);
    cpu->core_count = 0;

    /* Per-core is best effort: if it fails, the total is still reported. */
    if (pdh_get_double_array(c->cores, &c->arr) == 0) {
        unsigned count = 0;
        for (DWORD i = 0; i < c->arr.count; i++) {
            const PDH_FMT_COUNTERVALUE_ITEM_W *it = &c->arr.items[i];
            if (_wcsicmp(it->szName, L"_Total") == 0) continue;

            wchar_t *end;
            long idx = wcstol(it->szName, &end, 10);
            if (end == it->szName || *end != L'\0' || idx < 0 || idx >= MAX_CORES) continue;
            if (!pdh_cstatus_ok(it->FmtValue.CStatus)) continue;

            cpu->cores_pct[idx] = (float)clamp_pct(it->FmtValue.doubleValue);
            if ((unsigned)idx + 1 > count) count = (unsigned)idx + 1;
        }
        cpu->core_count = (uint16_t)count;
    }

    cpu->valid = true;
    return 0;
}

void cpu_shutdown(void *vctx) {
    cpu_ctx_t *c = vctx;
    if (!c) return;
    pdh_array_free(&c->arr);
    free(c);
    pdh_session_close();
}