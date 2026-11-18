#ifndef SOCKPATH_H
#define SOCKPATH_H

#include <stdbool.h>
#include <stddef.h>

/* Default socket location: %LOCALAPPDATA%\monitord\monitor.sock.
   With create_dir the directory is created if missing; it inherits the
   per-user permissions of %LOCALAPPDATA%, which is what restricts who can
   connect. Returns 0 on success, -1 if the path cannot be built or created. */
int sockpath_default(char *out, size_t cap, bool create_dir);

#endif