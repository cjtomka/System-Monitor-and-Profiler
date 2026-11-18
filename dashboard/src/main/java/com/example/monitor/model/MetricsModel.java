package com.example.monitor.model;

import com.example.monitor.net.ConnectionState;
import com.example.monitor.protocol.Message;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * What the views show. Everything here is touched on the FX thread only, and views get
 * read-only properties. The apply*() methods are for Session and tests.
 *
 * Views should listen to revisionProperty(): commit() bumps it at most once per frame, after
 * a whole batch of messages has been applied, so a burst of messages causes one refresh.
 */
public final class MetricsModel {
    public static final int DEFAULT_HISTORY = 600;
    private static final int MAX_HISTORY = 100_000;

    private final ReadOnlyObjectWrapper<ConnectionState> connectionState =
            new ReadOnlyObjectWrapper<>(ConnectionState.CONNECTING);
    private final ReadOnlyStringWrapper stateDetail = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper daemon = new ReadOnlyStringWrapper("");
    private final ReadOnlyIntegerWrapper cores = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper intervalMs = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper historyCapacity = new ReadOnlyIntegerWrapper(DEFAULT_HISTORY);
    private final ReadOnlyObjectWrapper<Message.Snapshot> latest = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyLongWrapper dropped = new ReadOnlyLongWrapper(0);
    private final ReadOnlyStringWrapper lastProblem = new ReadOnlyStringWrapper("");
    private final ReadOnlyLongWrapper revision = new ReadOnlyLongWrapper(0);

    private final ObservableList<Message.Snapshot> history = FXCollections.observableArrayList();
    private final ObservableList<Message.Snapshot> historyView = FXCollections.unmodifiableObservableList(history);
    private final HistoryBuffer buffer = new HistoryBuffer(DEFAULT_HISTORY);
    private boolean historyDirty;
    private boolean dirty;

    // ---- what views read --------------------------------------------------

    public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() { return connectionState.getReadOnlyProperty(); }
    public ConnectionState connectionState() { return connectionState.get(); }

    public ReadOnlyStringProperty stateDetailProperty() { return stateDetail.getReadOnlyProperty(); }
    public String stateDetail() { return stateDetail.get(); }

    public ReadOnlyStringProperty daemonProperty() { return daemon.getReadOnlyProperty(); }
    public String daemon() { return daemon.get(); }

    public ReadOnlyIntegerProperty coresProperty() { return cores.getReadOnlyProperty(); }
    public int cores() { return cores.get(); }

    public ReadOnlyIntegerProperty intervalMsProperty() { return intervalMs.getReadOnlyProperty(); }
    public int intervalMs() { return intervalMs.get(); }

    public ReadOnlyIntegerProperty historyCapacityProperty() { return historyCapacity.getReadOnlyProperty(); }
    public int historyCapacity() { return historyCapacity.get(); }

    /** The newest LIVE snapshot (it has the process list), or null before the first one. */
    public ReadOnlyObjectProperty<Message.Snapshot> latestProperty() { return latest.getReadOnlyProperty(); }
    public Message.Snapshot latest() { return latest.get(); }

    /** Snapshots the daemon discarded for this client under backpressure, since this connection began. */
    public ReadOnlyLongProperty droppedProperty() { return dropped.getReadOnlyProperty(); }
    public long dropped() { return dropped.get(); }

    public ReadOnlyStringProperty lastProblemProperty() { return lastProblem.getReadOnlyProperty(); }
    public String lastProblem() { return lastProblem.get(); }

    /** Increases by one in commit() whenever anything above changed. The property views should listen to. */
    public ReadOnlyLongProperty revisionProperty() { return revision.getReadOnlyProperty(); }
    public long revision() { return revision.get(); }

    /** Oldest first, sorted by seq, updated by commit(). Read-only. */
    public ObservableList<Message.Snapshot> history() { return historyView; }

    // ---- what Session writes ----------------------------------------------

    public void applyState(ConnectionState state, String detail) {
        connectionState.set(state);
        stateDetail.set(detail == null ? "" : detail);
        dirty = true;
    }

    /** A new connection: sequence numbers may have restarted, so start from an empty buffer. */
    public void beginConnection() {
        buffer.clear();
        historyDirty = true;
        latest.set(null);
        dropped.set(0);
        dirty = true;
    }

    public void applyHello(Message.Hello h) {
        daemon.set(h.daemon() == null ? "" : h.daemon());
        cores.set(h.cores());
        intervalMs.set(h.intervalMs());
        int capacity = h.historyCapacity() > 0 ? Math.min(h.historyCapacity(), MAX_HISTORY) : DEFAULT_HISTORY;
        historyCapacity.set(capacity);
        int before = buffer.size();
        buffer.setCapacity(capacity);
        if (buffer.size() != before) historyDirty = true;
        dirty = true;
    }

    public void applySnapshot(Message.Snapshot s) {
        Message.Snapshot current = latest.get();
        if (current == null || s.seq() > current.seq()) {      // a stale live snapshot never replaces a newer one
            latest.set(s);
            dropped.set(s.dropped());
        }
        buffer.addLive(s);
        historyDirty = true;
        dirty = true;
    }

    public void applyHistory(Message.History h) {
        if (h.snapshots() != null) buffer.addHistory(h.snapshots());
        historyDirty = true;
        dirty = true;
    }

    public void applyProblem(String text) {
        lastProblem.set(text == null ? "" : text);
        dirty = true;
    }

    /** Publishes buffered changes. Called once per frame, after a batch of events. */
    public void commit() {
        if (historyDirty) {
            history.setAll(buffer.toList());
            historyDirty = false;
        }
        if (dirty) {
            dirty = false;
            revision.set(revision.get() + 1);
        }
    }
}