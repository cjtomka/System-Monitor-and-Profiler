#ifndef SNAPSHOT_H
#define SNAPSHOT_H

#include <stdint.h>
#include <stdbool.h>

#define MAX_CORES       64
#define MAX_DISKS        8
#define MAX_TOP_PROCS   16
#define NAME_LEN        64

typedef struct {
    bool valid;
    double total_pct;
    uint16_t core_count;
    float cores_pct[MAX_CORES];
} cpu_stats_t;

typedef struct {
    bool     valid;
    uint64_t total_bytes;           /* read once at startup */
    uint64_t avail_bytes;
    uint64_t commit_bytes;
    uint64_t commit_limit_bytes;
} mem_stats_t;

typedef struct {
    char     name[16];              /* "C:" */
    uint64_t total_bytes;
    uint64_t free_bytes;
    uint64_t read_bps;
    uint64_t write_bps;
    double   queue;
} disk_stats_t;

typedef struct {
    uint32_t pid;
    char     name[NAME_LEN];        /* UTF-8, converted from PDH's wide string */
    float    cpu_pct;               /* already divided by logical core count */
    uint64_t rss_bytes;
} proc_stats_t;

typedef struct {
    uint64_t seq;
    uint64_t ts_ms;                 /* from GetSystemTimePreciseAsFileTime*/
    bool     warming_up;            /* first cycle: rate counters not valid yet */

    cpu_stats_t  cpu;
    mem_stats_t  mem;

    uint8_t      disk_count;        /* 0 + disks_valid=false => null */
    bool         disks_valid;
    disk_stats_t disks[MAX_DISKS];

    uint8_t      proc_count;
    bool         procs_valid;
    proc_stats_t procs[MAX_TOP_PROCS];
} snapshot_t;

#endif
