#ifndef CPU_PDH_H
#define CPU_PDH_H

#include "snapshot.h"

int  cpu_init(void **ctx);
int  cpu_sample(void *ctx, snapshot_t *out);
void cpu_shutdown(void *ctx);

#endif