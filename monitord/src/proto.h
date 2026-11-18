#ifndef PROTO_H
#define PROTO_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "snapshot.h"

#define PROTO_VERSION        1
#define PROTO_MAX_LINE_IN    1024            /* longest client line the daemon will parse */
#define PROTO_MAX_MSG_OUT    (64 * 1024)     /* every writer below fits in a buffer this size */
#define PROTO_HISTORY_CHUNK  16              /* snapshots per history message */

/* ---- writers ------------------------------------------------------------
   Each writes ONE complete message, including the trailing '\n', plus a NUL
   (not counted in the return value). Returns the message length, or -1 if it
   did not fit: cap must be at least length + 1. Never writes past cap.
   Thread-safe: no shared state. */

typedef struct {
    const char *daemon_version;      /* e.g. "monitord 0.1.0" */
    unsigned    cores;
    uint32_t    interval_ms;         /* current sampling interval */
    uint32_t    history_capacity;    /* ring buffer size, in snapshots */
} proto_hello_t;

int proto_write_hello(char *buf, size_t cap, const proto_hello_t *h);

/* `dropped` is the per-client count of snapshots discarded by backpressure. */
int proto_write_snapshot(char *buf, size_t cap, const snapshot_t *s, uint64_t dropped);

/* n snapshots, oldest first, WITHOUT the process list. The caller splits a
   longer history into chunks of PROTO_HISTORY_CHUNK and sets last on the final one. */
int proto_write_history(char *buf, size_t cap, const snapshot_t *snaps, size_t n, bool last);

int proto_write_error(char *buf, size_t cap, const char *code, const char *message);
int proto_write_pong(char *buf, size_t cap, bool has_id, int64_t id);

/* ---- command parsing ---------------------------------------------------- */

typedef enum { CMD_SET_INTERVAL, CMD_GET_HISTORY, CMD_PING } cmd_type_t;

typedef enum {
    PROTO_OK = 0,
    PROTO_ERR_BAD_JSON,        /* syntax error, trailing garbage, nesting too deep */
    PROTO_ERR_BAD_VERSION,     /* "v" present but not PROTO_VERSION */
    PROTO_ERR_UNKNOWN_TYPE,
    PROTO_ERR_MISSING_FIELD,   /* no "type", or a required field is absent */
    PROTO_ERR_BAD_VALUE,       /* wrong JSON type or out of range */
    PROTO_ERR_TOO_LONG         /* longer than PROTO_MAX_LINE_IN */
} proto_status_t;

typedef struct {
    cmd_type_t type;
    uint32_t   interval_ms;    /* CMD_SET_INTERVAL: NOT clamped here; sampler_set_interval clamps */
    uint32_t   count;          /* CMD_GET_HISTORY: 0 means "everything you have" */
    bool       has_id;         /* CMD_PING: optional id, echoed in the pong */
    int64_t    id;
} command_t;

/* `line` is one client line WITHOUT its newline and need not be NUL-terminated.
   `out` is only written on PROTO_OK. */
proto_status_t proto_parse_command(const char *line, size_t len, command_t *out);

const char *proto_status_code(proto_status_t s);      /* "bad_json", "unknown_type", ... */
const char *proto_status_message(proto_status_t s);   /* short human-readable text */

#endif