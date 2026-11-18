package com.example.monitor.ui;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.session.EventQueue;
import com.example.monitor.session.Session;
import javafx.animation.AnimationTimer;

/**
 * Runs on the FX thread once per frame: applies a bounded batch of queued events, then
 * publishes the result, so the views see one consistent update per frame.
 */
public final class ModelDriver extends AnimationTimer {
    static final int MAX_EVENTS_PER_FRAME = 200;       // a backlog is worked off over several frames

    private final EventQueue queue;
    private final Session session;
    private final MetricsModel model;

    public ModelDriver(EventQueue queue, Session session, MetricsModel model) {
        this.queue = queue;
        this.session = session;
        this.model = model;
    }

    @Override
    public void handle(long now) {
        queue.drainTo(session, MAX_EVENTS_PER_FRAME);
        model.commit();
    }
}
