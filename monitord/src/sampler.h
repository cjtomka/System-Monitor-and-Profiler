#ifndef SAMPLER_H
#define SAMPLER_H

#include <stddef.h>
#include <stdint.h>
#include "collector.h"
#include "ringbuf.h"

typedef struct {
    const collector_t *collectors;       /* static array, must outlive the sampler */
    size_t             n_collectors;
    void             (*begin_cycle)(void);   /* e.g. PdhCollectQueryData; may be NULL */
    ringbuf_t         *ring;                 /* owned by the caller */
    uint32_t           interval_ms;          /* clamped to 100..10000 */
} sampler_config_t;

typedef struct sampler sampler_t;            /* opaque */

sampler_t *sampler_create(const sampler_config_t *cfg);
int        sampler_start(sampler_t *s);                    /* 0 on success */
void       sampler_set_interval(sampler_t *s, uint32_t ms);
void       sampler_stop(sampler_t *s);                     /* idempotent */
void       sampler_destroy(sampler_t *s);/* stops if needed */

uint32_t   sampler_get_interval(sampler_t *s);                 /* effective (clamped) interval */
#endif