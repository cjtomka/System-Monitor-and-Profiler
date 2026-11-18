#include "disk_pdh.h"
#include "pdh_session.h"
#include "log.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

typedef struct {
    PDH_HCOUNTER read;          /* \LogicalDisk(*)\Disk Read Bytes/sec */
    PDH_HCOUNTER write;         /* \LogicalDisk(*)\Disk Write Bytes/sec */
    PDH_HCOUNTER queue;         /* \LogicalDisk(*)\Avg. Disk Queue Length */
    pdh_array_t  read_arr, write_arr, queue_arr;    /* reusable wildcard buffers */
    bool         warned_truncated;
} disk_ctx_t;

static uint64_t to_u64(double v) {
    if (!(v > 0.0)) return 0;               /* also catches NaN */
    return (uint64_t)v;
}

/* Finds an instance by name; NULL if absent or its status is not valid. */
static const PDH_FMT_COUNTERVALUE_ITEM_W *find_inst(const pdh_array_t *a, const wchar_t *name) {
    for (DWORD i = 0; i < a->count; i++) {
        const PDH_FMT_COUNTERVALUE_ITEM_W *it = &a->items[i];
        if (_wcsicmp(it->szName, name) == 0)
            return pdh_cstatus_ok(it->FmtValue.CStatus) ? it : NULL;
    }
    return NULL;
}

int disk_init(void **ctx) {
    disk_ctx_t *c = calloc(1, sizeof *c);
    if (!c) return -1;

    if (pdh_session_open() != 0) { free(c); return -1; }

    if (pdh_session_add_counter(L"\\LogicalDisk(*)\\Disk Read Bytes/sec",    &c->read)  != 0 ||
        pdh_session_add_counter(L"\\LogicalDisk(*)\\Disk Write Bytes/sec",   &c->write) != 0 ||
        pdh_session_add_counter(L"\\LogicalDisk(*)\\Avg. Disk Queue Length", &c->queue) != 0) {
        log_error("disk collector: LogicalDisk counters unavailable "
                  "(try 'lodctr /R' in an elevated prompt)");
        pdh_session_close();
        free(c);
        return -1;
    }
    *ctx = c;
    return 0;
}

int disk_sample(void *vctx, snapshot_t *out) {
    disk_ctx_t *c = vctx;

    switch (pdh_session_state()) {
    case PDH_SESSION_WARMING: return 0;     /* all three are rate counters: no value until collection 2 */
    case PDH_SESSION_FAILED:  return -1;
    case PDH_SESSION_READY:   break;
    }

    if (pdh_get_double_array(c->read,  &c->read_arr)  != 0 ||
        pdh_get_double_array(c->write, &c->write_arr) != 0 ||
        pdh_get_double_array(c->queue, &c->queue_arr) != 0)
        return -1;

    /* Walk drive letters A..Z instead of PDH's instance list. That gives a
       stable alphabetical order, and an exact-name lookup skips _Total and the
       HarddiskVolumeN instances (volumes without a letter) automatically. */
    DWORD   mask = GetLogicalDrives();
    uint8_t n = 0;

    for (int i = 0; i < 26; i++) {
        if (!(mask & (1u << i))) continue;

        wchar_t name[3] = { (wchar_t)(L'A' + i), L':', 0 };
        wchar_t root[4] = { (wchar_t)(L'A' + i), L':', L'\\', 0 };

        /* Fixed drives only: asking a disconnected network share or an empty
           optical drive for its free space can block the sampler for seconds. */
        if (GetDriveTypeW(root) != DRIVE_FIXED) continue;

        const PDH_FMT_COUNTERVALUE_ITEM_W *r = find_inst(&c->read_arr,  name);
        const PDH_FMT_COUNTERVALUE_ITEM_W *w = find_inst(&c->write_arr, name);
        const PDH_FMT_COUNTERVALUE_ITEM_W *q = find_inst(&c->queue_arr, name);
        if (!r || !w || !q) continue;       /* PDH has no data for this drive (yet) */

        ULARGE_INTEGER avail, total, total_free;
        if (!GetDiskFreeSpaceExW(root, &avail, &total, &total_free)) continue;   /* e.g. locked volume */

        if (n >= MAX_DISKS) {
            if (!c->warned_truncated) {
                log_warn("more than %d fixed drives; extra drives are not reported", MAX_DISKS);
                c->warned_truncated = true;
            }
            break;
        }

        disk_stats_t *d = &out->disks[n++];
        snprintf(d->name, sizeof d->name, "%c:", 'A' + i);
        d->total_bytes = total.QuadPart;
        d->free_bytes  = total_free.QuadPart;       /* total free, not the per-user quota figure */
        d->read_bps    = to_u64(r->FmtValue.doubleValue);
        d->write_bps   = to_u64(w->FmtValue.doubleValue);
        d->queue       = q->FmtValue.doubleValue > 0.0 ? q->FmtValue.doubleValue : 0.0;
    }

    out->disk_count  = n;
    out->disks_valid = true;                /* an empty list is still a valid reading */
    return 0;
}

void disk_shutdown(void *vctx) {
    disk_ctx_t *c = vctx;
    if (!c) return;
    pdh_array_free(&c->read_arr);
    pdh_array_free(&c->write_arr);
    pdh_array_free(&c->queue_arr);
    free(c);
    pdh_session_close();
}