#ifndef PDH_SESSION_H
#define PDH_SESSION_H

#include <windows.h>
#include <pdh.h>
#include <pdhmsg.h>
#include <stdbool.h>

/* One process-wide PDH query shared by every collector.
   Threading: open/add/close run on the main thread (inside sampler_create and
   sampler_destroy), collect/state/get run on the sampler thread. Those phases
   never overlap, so no lock is needed. */

typedef enum {
    PDH_SESSION_WARMING,   /* fewer than 2 collections: rate counters have no value yet */
    PDH_SESSION_READY,
    PDH_SESSION_FAILED     /* the last PdhCollectQueryData call failed */
} pdh_session_state_t;

/* Reference-counted: each collector's init opens, each shutdown closes. */
int  pdh_session_open(void);                       /* 0 on success */
void pdh_session_close(void);

/* Language-independent path, e.g. L"\\Processor(_Total)\\% Processor Time". */
int  pdh_session_add_counter(const wchar_t *path, PDH_HCOUNTER *out);

/* Pass as sampler_config_t.begin_cycle: one PdhCollectQueryData per cycle. */
void pdh_collect(void);
pdh_session_state_t pdh_session_state(void);

static inline bool pdh_cstatus_ok(DWORD s) {
    return s == PDH_CSTATUS_VALID_DATA || s == PDH_CSTATUS_NEW_DATA;
}

/* Single-instance counter as a double. 0 on success. */
int pdh_get_double(PDH_HCOUNTER c, double *out);

/* Wildcard counter: one item per instance. The buffer is owned by the caller,
   reused across cycles, and only ever grows, so steady state does not allocate. */
typedef struct {
    PDH_FMT_COUNTERVALUE_ITEM_W *items;
    DWORD                        cap_bytes;
    DWORD                        count;
} pdh_array_t;

int  pdh_get_double_array(PDH_HCOUNTER c, pdh_array_t *arr);   /* 0 on success */
void pdh_array_free(pdh_array_t *arr);

#endif