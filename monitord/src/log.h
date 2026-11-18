#ifndef LOG_H
#define LOG_H

#include <stdbool.h>

typedef enum { LOG_DEBUG = 0, LOG_INFO, LOG_WARN, LOG_ERROR } log_level_t;

#if defined(__GNUC__)
#define LOG_PRINTF(fmt_idx, arg_idx) __attribute__((format(printf, fmt_idx, arg_idx)))
#else
#define LOG_PRINTF(fmt_idx, arg_idx)
#endif

/* Optional: logging works before this is called (INFO and above, stderr only).
   file_path may be NULL. Returns 0 on success, -1 if the file could not be
   opened (stderr logging keeps working either way). */
int  log_init(log_level_t level, const char *file_path);
void log_shutdown(void);                      /* closes the file; stderr keeps working */

void log_set_level(log_level_t level);        /* thread-safe */
bool log_level_parse(const char *name, log_level_t *out);   /* for --log-level */

void log_msg(log_level_t level, const char *fmt, ...) LOG_PRINTF(2, 3);

#define log_debug(...) log_msg(LOG_DEBUG, __VA_ARGS__)
#define log_info(...)  log_msg(LOG_INFO,  __VA_ARGS__)
#define log_warn(...)  log_msg(LOG_WARN,  __VA_ARGS__)
#define log_error(...) log_msg(LOG_ERROR, __VA_ARGS__)

#endif