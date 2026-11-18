#include "mem_pdh.h"
#include "pdh_session.h"
#include "log.h"

#include <stdlib.h>

typedef struct {
    PDH_HCOUNTER avail;         /* \Memory\Available Bytes */
    PDH_HCOUNTER committed;     /* \Memory\Committed Bytes */
    PDH_HCOUNTER limit;         /* \Memory\Commit Limit */
    uint64_t     total_bytes;   /* not a PDH counter: read once at startup */
} mem_ctx_t;

static uint64_t to_u64(double v) {
    if (!(v > 0.0)) return 0;               /* also catches NaN */
    return (uint64_t)v;
}

int mem_init(void **ctx) {
    MEMORYSTATUSEX ms = { .dwLength = sizeof ms };
    if (!GlobalMemoryStatusEx(&ms)) {
        log_error("GlobalMemoryStatusEx failed (%lu)", (unsigned long)GetLastError());
        return -1;
    }

    mem_ctx_t *c = calloc(1, sizeof *c);
    if (!c) return -1;
    c->total_bytes = ms.ullTotalPhys;

    if (pdh_session_open() != 0) { free(c); return -1; }

    if (pdh_session_add_counter(L"\\Memory\\Available Bytes", &c->avail)      != 0 ||
        pdh_session_add_counter(L"\\Memory\\Committed Bytes", &c->committed)  != 0 ||
        pdh_session_add_counter(L"\\Memory\\Commit Limit",    &c->limit)      != 0) {
        pdh_session_close();
        free(c);
        return -1;
    }
    *ctx = c;
    return 0;
}

int mem_sample(void *vctx, snapshot_t *out) {
    mem_ctx_t *c = vctx;

    pdh_session_state_t st = pdh_session_state();
    if (st == PDH_SESSION_FAILED) return -1;

    /* Raw-count counters are readable after a single collection, so unlike CPU
       there is normally no warm-up gap here. If a read fails while the session
       is still warming up, stay quiet; afterwards it is a real failure. */
    double avail, committed, limit;
    if (pdh_get_double(c->avail,     &avail)     != 0 ||
        pdh_get_double(c->committed, &committed) != 0 ||
        pdh_get_double(c->limit,     &limit)     != 0)
        return (st == PDH_SESSION_WARMING) ? 0 : -1;

    mem_stats_t *m = &out->mem;
    m->total_bytes        = c->total_bytes;
    m->avail_bytes        = to_u64(avail);
    m->commit_bytes       = to_u64(committed);
    m->commit_limit_bytes = to_u64(limit);
    m->valid              = true;
    return 0;
}

void mem_shutdown(void *vctx) {
    free(vctx);
    pdh_session_close();         /* the session is reference-counted: CPU holds its own ref */
}