/* AF_UNIX listener, per-client send queues, command handling.
   One thread (server_main) owns every client. The only state it shares with
   other threads is the ring buffer (locked internally) and the sampler's
   interval (interlocked). */

#include "server.h"
#include "log.h"
#include "proto.h"
#include "sockpath.h"
#include "snapshot.h"

#include <winsock2.h>
#include <afunix.h>
#include <windows.h>

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_CLIENTS_HARD     16
#define DEFAULT_CLIENTS       8
#define DEFAULT_QUEUE_BYTES  (256 * 1024)
#define MIN_QUEUE_BYTES      (64 * 1024)
#define POLL_MS              50        /* select timeout, and the latency bound for new snapshots */
#define STALL_MS             10000     /* queued data but no send progress for this long => disconnect */
#define MAX_BAD_LINES        10        /* consecutive invalid commands before disconnecting */
#define RX_CAP               (PROTO_MAX_LINE_IN + 1)    /* +1 leaves room for a trailing '\r' */
#define PATH_CAP             108       /* sizeof(sockaddr_un.sun_path) */

/* ---- data --------------------------------------------------------------- */

typedef struct msg {
    struct msg *next;
    size_t      len;
    size_t      sent;
    bool        droppable;             /* a snapshot: may be discarded under backpressure */
    char        data[];
} msg_t;

typedef struct {
    SOCKET      sock;                  /* INVALID_SOCKET => slot is free */
    unsigned    id;
    size_t      queue_max;
    msg_t      *head, *tail;
    size_t      queued_bytes;          /* bytes not yet sent */
    uint64_t    dropped;               /* snapshots discarded for this client since it connected */
    ULONGLONG   last_progress;         /* last successful send, or when the queue went non-empty */

    char        rx[RX_CAP];
    size_t      rxlen;
    bool        discarding;            /* skipping the rest of an over-long line */
    unsigned    bad_lines;

    snapshot_t *hist;                  /* private copy of an in-progress history reply */
    size_t      hist_n, hist_pos;
    bool        hist_active;

    bool        doomed;                /* closed at the end of the current loop iteration */
    const char *why;
} client_t;

struct server {
    server_config_t cfg;
    char            path[PATH_CAP];
    SOCKET          listener;
    bool            owns_path;         /* we bound it, so we delete it */
    bool            wsa_started;
    HANDLE          thread;
    volatile LONG   stop;
    client_t        clients[MAX_CLIENTS_HARD];
    unsigned        next_id;
    uint64_t        last_seq;          /* newest snapshot already broadcast */
    char           *scratch;           /* PROTO_MAX_MSG_OUT: serialization buffer */
    snapshot_t     *snap;              /* copy of the newest snapshot */
};

static void doom(client_t *c, const char *why) {
    if (!c->doomed) { c->doomed = true; c->why = why; }
}

/* ---- send queue --------------------------------------------------------- */

/* Frees the oldest not-yet-started snapshots until `need` more bytes fit.
   A partly sent message is already on the wire and must finish. */
static void drop_oldest_snapshots(client_t *c, size_t need) {
    msg_t *prev = NULL, *m = c->head;
    while (m && c->queued_bytes + need > c->queue_max) {
        if (m->droppable && m->sent == 0) {
            msg_t *next = m->next;
            if (prev) prev->next = next; else c->head = next;
            if (c->tail == m) c->tail = prev;
            c->queued_bytes -= m->len;
            c->dropped++;
            free(m);
            m = next;
        } else {
            prev = m;
            m = m->next;
        }
    }
}

/* 0 = queued, 1 = a snapshot was dropped instead, -1 = a control message does not fit. */
static int enqueue(client_t *c, const char *data, size_t len, bool droppable) {
    if (c->queued_bytes + len > c->queue_max) drop_oldest_snapshots(c, len);
    if (c->queued_bytes + len > c->queue_max) {
        if (droppable) { c->dropped++; return 1; }
        return -1;
    }
    msg_t *m = malloc(sizeof *m + len);
    if (!m) {
        if (droppable) { c->dropped++; return 1; }
        return -1;
    }
    m->next = NULL; m->len = len; m->sent = 0; m->droppable = droppable;
    memcpy(m->data, data, len);

    if (!c->head) c->last_progress = GetTickCount64();      /* the stall clock starts now */
    if (c->tail) c->tail->next = m; else c->head = m;
    c->tail = m;
    c->queued_bytes += len;
    return 0;
}

/* Sends as much as the socket accepts. Returns false if the client must go. */
static bool flush(client_t *c) {
    while (c->head) {
        msg_t *m = c->head;
        int n = send(c->sock, m->data + m->sent, (int)(m->len - m->sent), 0);
        if (n == SOCKET_ERROR) {
            int e = WSAGetLastError();
            if (e == WSAEWOULDBLOCK) return true;
            log_debug("client %u: send failed (WSA %d)", c->id, e);
            return false;
        }
        if (n == 0) return true;
        m->sent += (size_t)n;
        c->queued_bytes -= (size_t)n;
        c->last_progress = GetTickCount64();
        if (m->sent == m->len) {
            c->head = m->next;
            if (!c->head) c->tail = NULL;
            free(m);
        }
    }
    return true;
}

static void client_close(client_t *c, const char *why) {
    log_info("client %u disconnected: %s (%llu snapshots dropped)",
             c->id, why ? why : "closed", (unsigned long long)c->dropped);
    for (msg_t *m = c->head; m; ) { msg_t *n = m->next; free(m); m = n; }
    free(c->hist);
    if (c->sock != INVALID_SOCKET) closesocket(c->sock);
    memset(c, 0, sizeof *c);
    c->sock = INVALID_SOCKET;
}

/* `n` is the length returned by a proto_write_* call that used s->scratch. */
static void send_control(server_t *s, client_t *c, int n) {
    if (n < 0 || enqueue(c, s->scratch, (size_t)n, false) < 0)
        doom(c, "send queue overflow");
}

/* ---- messages ----------------------------------------------------------- */

static void send_hello(server_t *s, client_t *c) {
    proto_hello_t h = {
        s->cfg.daemon_version,
        s->cfg.cores,
        sampler_get_interval(s->cfg.sampler),
        s->cfg.history_capacity,
    };
    send_control(s, c, proto_write_hello(s->scratch, PROTO_MAX_MSG_OUT, &h));
}

static void broadcast_hello(server_t *s) {
    for (int i = 0; i < MAX_CLIENTS_HARD; i++) {
        client_t *c = &s->clients[i];
        if (c->sock != INVALID_SOCKET && !c->doomed) send_hello(s, c);
    }
}

/* Feeds history chunks into the queue only while it is below a low-water mark,
   so a big history reply never swamps the queue or starves live snapshots. */
static void pump_history(server_t *s, client_t *c) {
    size_t low_water = c->queue_max / 4;
    while (c->hist_active && !c->doomed && c->queued_bytes < low_water) {
        size_t remaining = c->hist_n - c->hist_pos;
        size_t n = remaining < PROTO_HISTORY_CHUNK ? remaining : PROTO_HISTORY_CHUNK;
        bool last = (c->hist_pos + n >= c->hist_n);
        send_control(s, c, proto_write_history(s->scratch, PROTO_MAX_MSG_OUT,
                                               c->hist + c->hist_pos, n, last));
        c->hist_pos += n;
        if (last) { free(c->hist); c->hist = NULL; c->hist_active = false; }
    }
}

static void start_history(server_t *s, client_t *c, uint32_t count) {
    if (c->hist_active) {
        send_control(s, c, proto_write_error(s->scratch, PROTO_MAX_MSG_OUT, "busy",
                                             "A history reply is already in progress."));
        return;
    }
    size_t want = s->cfg.history_capacity;
    if (count != 0 && count < want) want = count;
    if (want == 0) want = 1;

    snapshot_t *buf = malloc(want * sizeof *buf);
    if (!buf) {
        send_control(s, c, proto_write_error(s->scratch, PROTO_MAX_MSG_OUT, "internal", "Out of memory."));
        return;
    }
    c->hist_n = ringbuf_copy_last(s->cfg.ring, want, buf);   /* oldest first; may be 0 */
    c->hist = buf;
    c->hist_pos = 0;
    c->hist_active = true;
    pump_history(s, c);
}

static void handle_line(server_t *s, client_t *c, const char *line, size_t len) {
    if (len == 0) return;                                    /* ignore blank lines */

    command_t cmd;
    proto_status_t st = proto_parse_command(line, len, &cmd);
    if (st != PROTO_OK) {
        if (++c->bad_lines > MAX_BAD_LINES) { doom(c, "too many invalid commands"); return; }
        send_control(s, c, proto_write_error(s->scratch, PROTO_MAX_MSG_OUT,
                                             proto_status_code(st), proto_status_message(st)));
        return;
    }
    c->bad_lines = 0;

    switch (cmd.type) {
    case CMD_PING:
        send_control(s, c, proto_write_pong(s->scratch, PROTO_MAX_MSG_OUT, cmd.has_id, cmd.id));
        break;
    case CMD_SET_INTERVAL: {
        uint32_t before = sampler_get_interval(s->cfg.sampler);
        sampler_set_interval(s->cfg.sampler, cmd.interval_ms);
        uint32_t after = sampler_get_interval(s->cfg.sampler);
        if (after != before) {
            log_info("client %u set the interval to %u ms (effective: %u ms)",
                     c->id, cmd.interval_ms, after);
            broadcast_hello(s);                              /* the interval changed: everyone learns it */
        } else {
            send_hello(s, c);                                /* no change: just confirm to the requester */
        }
        break;
    }
    case CMD_GET_HISTORY:
        start_history(s, c, cmd.count);
        break;
    }
}

/* ---- reading ------------------------------------------------------------ */

static void service_reads(server_t *s, client_t *c) {
    char tmp[2048];
    int n = recv(c->sock, tmp, sizeof tmp, 0);
    if (n == 0) { doom(c, "closed by peer"); return; }
    if (n == SOCKET_ERROR) {
        int e = WSAGetLastError();
        if (e == WSAEWOULDBLOCK) return;
        if (e == WSAECONNRESET || e == WSAECONNABORTED) {
            doom(c, "connection reset by peer");       /* the client exited with unread data */
        } else {
            log_warn("client %u: recv failed (WSA %d)", c->id, e);
            doom(c, "receive error");
        }
        return;
    }

    for (int i = 0; i < n && !c->doomed; i++) {
        char ch = tmp[i];
        if (ch == '\n') {
            if (c->discarding) {
                c->discarding = false;                       /* resynchronized */
            } else {
                size_t len = c->rxlen;
                if (len > 0 && c->rx[len - 1] == '\r') len--;
                handle_line(s, c, c->rx, len);
            }
            c->rxlen = 0;
        } else if (c->discarding) {
            /* still skipping the tail of an over-long line */
        } else if (c->rxlen < RX_CAP) {
            c->rx[c->rxlen++] = ch;
        } else {
            send_control(s, c, proto_write_error(s->scratch, PROTO_MAX_MSG_OUT, "line_too_long",
                                                 proto_status_message(PROTO_ERR_TOO_LONG)));
            c->discarding = true;
            c->rxlen = 0;
        }
    }
}

/* ---- accepting and broadcasting ----------------------------------------- */

static unsigned active_clients(const server_t *s) {
    unsigned n = 0;
    for (int i = 0; i < MAX_CLIENTS_HARD; i++)
        if (s->clients[i].sock != INVALID_SOCKET) n++;
    return n;
}

static client_t *free_slot(server_t *s) {
    for (int i = 0; i < MAX_CLIENTS_HARD; i++)
        if (s->clients[i].sock == INVALID_SOCKET) return &s->clients[i];
    return NULL;
}

static void accept_clients(server_t *s) {
    for (;;) {
        SOCKET cs = accept(s->listener, NULL, NULL);
        if (cs == INVALID_SOCKET) {
            int e = WSAGetLastError();
            if (e != WSAEWOULDBLOCK) log_warn("accept failed (WSA %d)", e);
            return;
        }
        u_long nb = 1;
        ioctlsocket(cs, FIONBIO, &nb);

        client_t *c = free_slot(s);
        if (!c || active_clients(s) >= s->cfg.max_clients) {
            int n = proto_write_error(s->scratch, PROTO_MAX_MSG_OUT, "too_many_clients",
                                      "The daemon has reached its client limit.");
            if (n > 0) send(cs, s->scratch, n, 0);           /* best effort */
            closesocket(cs);
            log_warn("rejected a connection: client limit (%u) reached", s->cfg.max_clients);
            continue;
        }

        memset(c, 0, sizeof *c);
        c->sock = cs;
        c->id = ++s->next_id;
        c->queue_max = s->cfg.client_queue_bytes;
        c->last_progress = GetTickCount64();
        log_info("client %u connected (%u active)", c->id, active_clients(s));
        send_hello(s, c);
    }
}

/* Sends the newest snapshot to every client. Each client gets its own copy of
   the text because the `dropped` field differs per client. */
static void broadcast_snapshot(server_t *s) {
    uint64_t seq;
    if (!ringbuf_latest_seq(s->cfg.ring, &seq) || seq == s->last_seq) return;
    if (!ringbuf_latest(s->cfg.ring, s->snap)) return;
    s->last_seq = s->snap->seq;

    for (int i = 0; i < MAX_CLIENTS_HARD; i++) {
        client_t *c = &s->clients[i];
        if (c->sock == INVALID_SOCKET || c->doomed) continue;
        int n = proto_write_snapshot(s->scratch, PROTO_MAX_MSG_OUT, s->snap, c->dropped);
        if (n < 0) { log_error("snapshot did not fit the output buffer"); continue; }
        enqueue(c, s->scratch, (size_t)n, true);             /* drops are counted inside */
    }
}

/* ---- the server thread -------------------------------------------------- */

static DWORD WINAPI server_main(LPVOID arg) {
    server_t *s = arg;

    while (InterlockedCompareExchange(&s->stop, 0, 0) == 0) {
        fd_set rfds, wfds;
        FD_ZERO(&rfds);
        FD_ZERO(&wfds);
        FD_SET(s->listener, &rfds);
        for (int i = 0; i < MAX_CLIENTS_HARD; i++) {
            client_t *c = &s->clients[i];
            if (c->sock == INVALID_SOCKET) continue;
            FD_SET(c->sock, &rfds);
            if (c->head) FD_SET(c->sock, &wfds);
        }

        struct timeval tv = { 0, POLL_MS * 1000 };
        int r = select(0, &rfds, &wfds, NULL, &tv);
        if (r == SOCKET_ERROR) {
            log_error("select failed (WSA %d)", WSAGetLastError());
            Sleep(POLL_MS);
            continue;
        }

        if (r > 0 && FD_ISSET(s->listener, &rfds)) accept_clients(s);

        for (int i = 0; i < MAX_CLIENTS_HARD; i++) {
            client_t *c = &s->clients[i];
            if (c->sock != INVALID_SOCKET && !c->doomed && r > 0 && FD_ISSET(c->sock, &rfds))
                service_reads(s, c);
        }

        broadcast_snapshot(s);

        ULONGLONG now = GetTickCount64();
        for (int i = 0; i < MAX_CLIENTS_HARD; i++) {
            client_t *c = &s->clients[i];
            if (c->sock == INVALID_SOCKET) continue;
            if (!c->doomed) {
                pump_history(s, c);
                if (!flush(c)) doom(c, "send failed");
            }
            if (!c->doomed && c->head && now - c->last_progress > STALL_MS)
                doom(c, "stalled: not reading");
            if (c->doomed) client_close(c, c->why);
        }
    }
    return 0;
}

/* ---- lifecycle ---------------------------------------------------------- */

static bool socket_is_live(const struct sockaddr_un *addr) {
    SOCKET t = socket(AF_UNIX, SOCK_STREAM, 0);
    if (t == INVALID_SOCKET) return false;
    bool live = (connect(t, (const struct sockaddr *)addr, sizeof *addr) == 0);
    closesocket(t);
    return live;
}

server_t *server_create(const server_config_t *cfg) {
    WSADATA wsa;
    struct sockaddr_un addr;
    u_long nb = 1;
    int werr;

    if (!cfg || !cfg->ring || !cfg->sampler) return NULL;
    server_t *s = calloc(1, sizeof *s);
    if (!s) return NULL;

    s->cfg = *cfg;
    s->listener = INVALID_SOCKET;
    for (int i = 0; i < MAX_CLIENTS_HARD; i++) s->clients[i].sock = INVALID_SOCKET;
    if (s->cfg.max_clients == 0)                       s->cfg.max_clients = DEFAULT_CLIENTS;
    if (s->cfg.max_clients > MAX_CLIENTS_HARD)         s->cfg.max_clients = MAX_CLIENTS_HARD;
    if (s->cfg.client_queue_bytes == 0)                s->cfg.client_queue_bytes = DEFAULT_QUEUE_BYTES;
    if (s->cfg.client_queue_bytes < MIN_QUEUE_BYTES)   s->cfg.client_queue_bytes = MIN_QUEUE_BYTES;

    if (cfg->socket_path && cfg->socket_path[0]) {
        if (strlen(cfg->socket_path) >= PATH_CAP) {
            log_error("socket path is too long (the limit is %d characters)", PATH_CAP - 1);
            goto fail;
        }
        strcpy(s->path, cfg->socket_path);
    } else if (sockpath_default(s->path, sizeof s->path, true) != 0) {
        log_error("cannot work out the default socket path (is LOCALAPPDATA set?)");
        goto fail;
    }
    s->cfg.socket_path = s->path;
    for (const unsigned char *p = (const unsigned char *)s->path; *p; p++) {
        if (*p >= 0x80) {
            log_warn("the socket path has non-ASCII characters; if clients cannot connect, "
                     "pick an ASCII path with --socket");
            break;
        }
    }

    s->scratch = malloc(PROTO_MAX_MSG_OUT);
    s->snap = malloc(sizeof *s->snap);
    if (!s->scratch || !s->snap) goto fail;

    werr = WSAStartup(MAKEWORD(2, 2), &wsa);
    if (werr != 0) { log_error("WSAStartup failed (%d)", werr); goto fail; }
    s->wsa_started = true;

    s->listener = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s->listener == INVALID_SOCKET) {
        log_error("cannot create an AF_UNIX socket (WSA %d); this needs Windows 10 version 1803 "
                  "or later", WSAGetLastError());
        goto fail;
    }

    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path, s->path, strlen(s->path) + 1);

    if (GetFileAttributesA(s->path) != INVALID_FILE_ATTRIBUTES) {
        if (socket_is_live(&addr)) {
            log_error("another monitord is already listening on %s", s->path);
            goto fail;
        }
        log_info("removing stale socket file %s", s->path);
        if (!DeleteFileA(s->path)) {
            log_error("cannot remove the stale socket file %s (error %lu)",
                      s->path, (unsigned long)GetLastError());
            goto fail;
        }
    }

    if (bind(s->listener, (const struct sockaddr *)&addr, sizeof addr) == SOCKET_ERROR) {
        log_error("bind(%s) failed (WSA %d): check that the directory exists and is writable",
                  s->path, WSAGetLastError());
        goto fail;
    }
    s->owns_path = true;                                     /* from here on we delete the file */

    if (listen(s->listener, 4) == SOCKET_ERROR || ioctlsocket(s->listener, FIONBIO, &nb) == SOCKET_ERROR) {
        log_error("cannot start listening (WSA %d)", WSAGetLastError());
        goto fail;
    }
    return s;

fail:
    server_destroy(s);
    return NULL;
}

int server_start(server_t *s) {
    if (!s || s->thread) return -1;
    InterlockedExchange(&s->stop, 0);
    s->thread = CreateThread(NULL, 0, server_main, s, 0, NULL);
    return s->thread ? 0 : -1;
}

void server_stop(server_t *s) {
    if (!s || !s->thread) return;
    InterlockedExchange(&s->stop, 1);
    WaitForSingleObject(s->thread, INFINITE);
    CloseHandle(s->thread);
    s->thread = NULL;
}

void server_destroy(server_t *s) {
    if (!s) return;
    server_stop(s);
    for (int i = 0; i < MAX_CLIENTS_HARD; i++)
        if (s->clients[i].sock != INVALID_SOCKET) client_close(&s->clients[i], "server shutting down");
    if (s->listener != INVALID_SOCKET) closesocket(s->listener);
    if (s->owns_path) DeleteFileA(s->path);
    if (s->wsa_started) WSACleanup();
    free(s->scratch);
    free(s->snap);
    free(s);
}

const char *server_socket_path(const server_t *s) { return s ? s->path : ""; }