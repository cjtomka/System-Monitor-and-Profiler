#include "sockpath.h"

#include <windows.h>
#include <stdio.h>

int sockpath_default(char *out, size_t cap, bool create_dir) {
    char base[MAX_PATH];
    DWORD n = GetEnvironmentVariableA("LOCALAPPDATA", base, sizeof base);
    if (n == 0 || n >= sizeof base) return -1;

    char dir[MAX_PATH + 16];
    int w = snprintf(dir, sizeof dir, "%s\\monitord", base);
    if (w < 0 || (size_t)w >= sizeof dir) return -1;

    if (create_dir && !CreateDirectoryA(dir, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
        return -1;

    w = snprintf(out, cap, "%s\\monitor.sock", dir);
    return (w < 0 || (size_t)w >= cap) ? -1 : 0;
}