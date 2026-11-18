package com.example.monitor.session;

import com.example.monitor.net.ConnectionManager;
import com.example.monitor.net.ConnectionState;
import com.example.monitor.protocol.Message;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The hand-off between the connection thread and the FX thread. As a ConnectionManager
 * Listener it only enqueues, so it never blocks the connection thread; the FX thread calls
 * drainTo() once per frame.
 */
public final class EventQueue implements ConnectionManager.Listener {

    sealed interface Event {
        record State(ConnectionState state, String detail) implements Event {}
        record Msg(Message message) implements Event {}
        record Problem(String text) implements Event {}
    }

    /** Past this many pending events new snapshots are discarded, in case the FX thread stalls. */
    static final int MAX_PENDING = 10_000;

    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong discarded = new AtomicLong();

    @Override
    public void onState(ConnectionState state, String detail) {
        add(new Event.State(state, detail));
    }

    @Override
    public void onMessage(Message message) {
        if (message instanceof Message.Snapshot && pending.get() >= MAX_PENDING) {
            discarded.incrementAndGet();               // only snapshots are ever discarded
            return;
        }
        add(new Event.Msg(message));
    }

    @Override
    public void onProblem(String description) {
        add(new Event.Problem(description));
    }

    /** FX thread: applies up to `max` queued events, oldest first. Returns how many were applied. */
    public int drainTo(Session session, int max) {
        int n = 0;
        Event e;
        while (n < max && (e = queue.poll()) != null) {
            pending.decrementAndGet();
            if (e instanceof Event.State s)        session.onState(s.state(), s.detail());
            else if (e instanceof Event.Msg m)     session.onMessage(m.message());
            else if (e instanceof Event.Problem p) session.onProblem(p.text());
            n++;
        }
        return n;
    }

    public boolean hasPending() {
        return pending.get() > 0;
    }

    public long discarded() {
        return discarded.get();
    }

    private void add(Event e) {
        queue.add(e);
        pending.incrementAndGet();
    }
}