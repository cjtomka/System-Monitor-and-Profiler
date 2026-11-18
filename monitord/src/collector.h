#include "snapshot.h"
#ifndef COLLECTOR_H
#define COLLECTOR_H


typedef struct collector {
    const char* name;
    int (*init)(void **ctx);
    int (*sample)(void *ctx, snapshot_t *out);
    void (*shutdown)(void *ctx);
} collector_t;


#endif