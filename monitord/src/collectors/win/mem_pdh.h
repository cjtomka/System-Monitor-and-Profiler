#ifndef MEM_PDH_H
#define MEM_PDH_H

#include "snapshot.h"

int  mem_init(void **ctx);
int  mem_sample(void *ctx, snapshot_t *out);
void mem_shutdown(void *ctx);

#endif