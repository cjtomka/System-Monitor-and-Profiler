#ifndef DISK_PDH_H
#define DISK_PDH_H

#include "snapshot.h"

int  disk_init(void **ctx);
int  disk_sample(void *ctx, snapshot_t *out);
void disk_shutdown(void *ctx);

#endif