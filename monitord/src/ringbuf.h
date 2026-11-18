#ifndef RINGBUF_H
#define RINGBUF_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "snapshot.h"

typedef struct ringbuf ringbuf_t;            /* opaque: no windows.h in the header */

/* Allocates all storage up front. Returns NULL if capacity is 0 or allocation fails. */
ringbuf_t *ringbuf_create(size_t capacity);
void       ringbuf_destroy(ringbuf_t *rb);   /* caller guarantees no thread is using it */

/* Writer (sampler thread). Overwrites the oldest entry when full. */
void ringbuf_push(ringbuf_t *rb, const snapshot_t *s);

/* Readers (server thread). All return copies, never pointers into the buffer. */
bool   ringbuf_latest(ringbuf_t *rb, snapshot_t *out);       /* false if empty */
bool   ringbuf_latest_seq(ringbuf_t *rb, uint64_t *seq);     /* cheap "anything new?" check */
size_t ringbuf_copy_last(ringbuf_t *rb, size_t n, snapshot_t *out);

#endif