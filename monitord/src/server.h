#ifndef SERVER_H
#define SERVER_H

#include <stddef.h>
#include <stdint.h>
#include "ringbuf.h"
#include "sampler.h"

typedef struct server server_t;

typedef struct {
    const char *socket_path;         /* NULL or "" => sockpath_default() */
    ringbuf_t  *ring;                /* owned by the caller, must outlive the server */
    sampler_t  *sampler;             /* same; used for set_interval and the hello interval */
    const char *daemon_version;      /* must outlive the server (a string literal is fine) */
    unsigned    cores;
    uint32_t    history_capacity;    /* ring capacity, advertised in hello */
    unsigned    max_clients;         /* 0 => 8, at most 16 */
    size_t      client_queue_bytes;  /* per-client send queue; 0 => 256 KB, minimum 64 KB */
} server_config_t;

/* Binds and listens. Returns NULL (after logging why) if it cannot, including
   when another monitord already owns the socket. */
server_t   *server_create(const server_config_t *cfg);
int         server_start(server_t *s);          /* 0 on success */
void        server_stop(server_t *s);           /* idempotent; joins the thread */
void        server_destroy(server_t *s);        /* stops, closes clients, removes the socket file */
const char *server_socket_path(const server_t *s);

#endif