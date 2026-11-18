#include "pdh_session.h"
#include "log.h"

#include <limits.h>
#include <stdlib.h>

static PDH_HQUERY          g_query       = NULL;
static int                 g_refs        = 0;
static unsigned            g_collections = 0;
static pdh_session_state_t g_state       = PDH_SESSION_WARMING;

int pdh_session_open(void) {
    if (g_refs > 0) { g_refs++; return 0; }

    PDH_STATUS st = PdhOpenQueryW(NULL, 0, &g_query);
    if (st != ERROR_SUCCESS) {
        log_error("PdhOpenQuery failed (0x%08lX)", (unsigned long)st);
        g_query = NULL;
        return -1;
    }
    g_refs        = 1;
    g_collections = 0;
    g_state       = PDH_SESSION_WARMING;
    return 0;
}

void pdh_session_close(void) {
    if (g_refs == 0) return;
    if (--g_refs > 0) return;
    PdhCloseQuery(g_query);            /* also releases every counter added to it */
    g_query = NULL;
}

int pdh_session_add_counter(const wchar_t *path, PDH_HCOUNTER *out) {
    if (!g_query) return -1;
    PDH_STATUS st = PdhAddEnglishCounterW(g_query, path, 0, out);
    if (st != ERROR_SUCCESS) {
        log_error("cannot add PDH counter %ls (0x%08lX)", path, (unsigned long)st);
        return -1;
    }
    return 0;
}

void pdh_collect(void) {
    if (!g_query) return;

    PDH_STATUS st = PdhCollectQueryData(g_query);
    if (st == ERROR_SUCCESS) {
        if (g_state == PDH_SESSION_FAILED) log_info("PDH collection recovered");
        if (g_collections < UINT_MAX) g_collections++;
        g_state = (g_collections >= 2) ? PDH_SESSION_READY : PDH_SESSION_WARMING;
    } else {
        if (g_state != PDH_SESSION_FAILED)            /* log once per failure episode */
            log_warn("PdhCollectQueryData failed (0x%08lX)", (unsigned long)st);
        g_state = PDH_SESSION_FAILED;
    }
}

pdh_session_state_t pdh_session_state(void) { return g_state; }

int pdh_get_double(PDH_HCOUNTER c, double *out) {
    PDH_FMT_COUNTERVALUE v;
    PDH_STATUS st = PdhGetFormattedCounterValue(c, PDH_FMT_DOUBLE, NULL, &v);
    if (st != ERROR_SUCCESS || !pdh_cstatus_ok(v.CStatus)) return -1;
    *out = v.doubleValue;
    return 0;
}

int pdh_get_double_array(PDH_HCOUNTER c, pdh_array_t *arr) {
    DWORD bytes = arr->cap_bytes, count = 0;
    PDH_STATUS st = PdhGetFormattedCounterArrayW(c, PDH_FMT_DOUBLE, &bytes, &count, arr->items);

    if (st == PDH_MORE_DATA) {                         /* first call, or more instances appeared */
        void *p = realloc(arr->items, bytes);
        if (!p) return -1;
        arr->items     = p;
        arr->cap_bytes = bytes;
        st = PdhGetFormattedCounterArrayW(c, PDH_FMT_DOUBLE, &bytes, &count, arr->items);
    }
    if (st != ERROR_SUCCESS) return -1;
    arr->count = count;
    return 0;
}

void pdh_array_free(pdh_array_t *arr) {
    free(arr->items);
    arr->items = NULL;
    arr->cap_bytes = arr->count = 0;
}