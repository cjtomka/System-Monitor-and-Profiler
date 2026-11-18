#ifndef PROC_PDH_H
#define PROC_PDH_H

#include "snapshot.h"

int  proc_init(void **ctx);
int  proc_sample(void *ctx, snapshot_t *out);
void proc_shutdown(void *ctx);

#endif