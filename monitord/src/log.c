#include "log.h"

#include <windows.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define LINE_MAX_LEN 1024

/* Statically initialized, so logging is safe before log_init() runs. */
static SRWLOCK       g_lock  = SRWLOCK_INIT;
static FILE         *g_file  = NULL;            /* guarded by g_lock */
static volatile LONG g_level = LOG_INFO;        /* read without the lock */

static const char *const LEVEL_NAMES[] = { "DEBUG", "INFO ", "WARN ", "ERROR" };

void log_set_level(log_level_t level) {
    InterlockedExchange(&g_level, (LONG)level);
}

bool log_level_parse(const char *name, log_level_t *out) {
    static const struct { const char *name; log_level_t level; } map[] = {
        { "debug", LOG_DEBUG }, { "info", LOG_INFO },
        { "warn",  LOG_WARN  }, { "error", LOG_ERROR },
    };
    if (!name) return false;
    for (size_t i = 0; i < sizeof map / sizeof map[0]; i++) {
        if (_stricmp(name, map[i].name) == 0) { *out = map[i].level; return true; }
    }
    return false;
}

int log_init(log_level_t level, const char *file_path) {
    log_set_level(level);
    if (!file_path) return 0;

    FILE *f = fopen(file_path, "a");            /* append; text mode gives CRLF */
    if (!f) {
        log_warn("cannot open log file '%s'; logging to stderr only", file_path);
        return -1;
    }

    AcquireSRWLockExclusive(&g_lock);
    FILE *old = g_file;
    g_file = f;
    ReleaseSRWLockExclusive(&g_lock);
    if (old) fclose(old);
    return 0;
}

void log_shutdown(void) {
    AcquireSRWLockExclusive(&g_lock);
    FILE *old = g_file;
    g_file = NULL;
    ReleaseSRWLockExclusive(&g_lock);
    if (old) fclose(old);
}

void log_msg(log_level_t level, const char *fmt, ...) {
    /* Cheap filter first: a suppressed debug call costs one atomic read. */
    if ((LONG)level < InterlockedCompareExchange(&g_level, 0, 0)) return;
    if (level < LOG_DEBUG || level > LOG_ERROR) level = LOG_ERROR;

    /* Format the whole line OUTSIDE the lock, so the lock is held only
       for the write and lines from different threads never interleave. */
    char line[LINE_MAX_LEN];
    SYSTEMTIME t;
    GetLocalTime(&t);

    int n = snprintf(line, sizeof line,
                     "%04u-%02u-%02u %02u:%02u:%02u.%03u [%s] [t%lu] ",
                     t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond,
                     t.wMilliseconds, LEVEL_NAMES[level],
                     (unsigned long)GetCurrentThreadId());
    if (n < 0) return;

    /* Leave room for the trailing newline and the NUL. */
    va_list ap;
    va_start(ap, fmt);
    int m = vsnprintf(line + n, sizeof line - (size_t)n - 1, fmt, ap);
    va_end(ap);

    size_t len = (size_t)n + (m < 0 ? 0 : (size_t)m);
    if (len > sizeof line - 2) len = sizeof line - 2;        /* truncated message */
    if (len == 0 || line[len - 1] != '\n') line[len++] = '\n';
    line[len] = '\0';

    AcquireSRWLockExclusive(&g_lock);
    fwrite(line, 1, len, stderr);
    if (g_file) {
        fwrite(line, 1, len, g_file);
        fflush(g_file);                                      /* low volume, so flush every line */
    }
    ReleaseSRWLockExclusive(&g_lock);
}