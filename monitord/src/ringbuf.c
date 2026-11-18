#include "ringbuf.h"
#include <windows.h>
#include <stdlib.h>
#include <string.h>

struct ringbuf {
    SRWLOCK     lock;
    snapshot_t *buf;        /* capacity entries, allocated once */
    size_t      capacity;
    size_t      head;       /* index of the NEXT slot to write */
    size_t      count;      /* valid entries, <= capacity */
};

ringbuf_t *ringbuf_create(size_t capacity) {
    if (capacity == 0) return NULL;

    ringbuf_t *rb = calloc(1, sizeof *rb);
    if (!rb) return NULL;

    rb->buf = calloc(capacity, sizeof *rb->buf);   /* calloc checks the multiply overflow */
    if (!rb->buf) { free(rb); return NULL; }

    rb->capacity = capacity;
    InitializeSRWLock(&rb->lock);                  /* SRWLOCKs need no destroy call */
    return rb;
}

void ringbuf_destroy(ringbuf_t *rb) {
    if (!rb) return;
    free(rb->buf);
    free(rb);
}

void ringbuf_push(ringbuf_t *rb, const snapshot_t *s) {
    AcquireSRWLockExclusive(&rb->lock);
    memcpy(&rb->buf[rb->head], s, sizeof *s);
    rb->head = (rb->head + 1) % rb->capacity;
    if (rb->count < rb->capacity) rb->count++;
    ReleaseSRWLockExclusive(&rb->lock);
}

bool ringbuf_latest(ringbuf_t *rb, snapshot_t *out) {
    bool ok = false;
    AcquireSRWLockShared(&rb->lock);
    if (rb->count > 0) {
        size_t idx = (rb->head + rb->capacity - 1) % rb->capacity;
        memcpy(out, &rb->buf[idx], sizeof *out);
        ok = true;
    }
    ReleaseSRWLockShared(&rb->lock);
    return ok;
}

bool ringbuf_latest_seq(ringbuf_t *rb, uint64_t *seq) {
    bool ok = false;
    AcquireSRWLockShared(&rb->lock);
    if (rb->count > 0) {
        size_t idx = (rb->head + rb->capacity - 1) % rb->capacity;
        *seq = rb->buf[idx].seq;
        ok = true;
    }
    ReleaseSRWLockShared(&rb->lock);
    return ok;
}

/* Copies up to n of the most recent snapshots into out, OLDEST FIRST.
   Returns how many were copied (fewer than n if the buffer holds fewer).
   `out` must have room for n entries. */
size_t ringbuf_copy_last(ringbuf_t *rb, size_t n, snapshot_t *out) {
    AcquireSRWLockShared(&rb->lock);
    if (n > rb->count) n = rb->count;

    /* the oldest of the n entries sits n slots behind head */
    size_t start = (rb->head + rb->capacity - n) % rb->capacity;
    for (size_t i = 0; i < n; i++)
        memcpy(&out[i], &rb->buf[(start + i) % rb->capacity], sizeof *out);

    ReleaseSRWLockShared(&rb->lock);
    return n;
}