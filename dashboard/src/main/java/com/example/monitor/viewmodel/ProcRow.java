package com.example.monitor.viewmodel;

import com.example.monitor.protocol.Message;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** One table row. The identity is the PID; everything else updates in place. */
public final class ProcRow {
    private final long pid;
    private final StringProperty name = new SimpleStringProperty("");
    private final DoubleProperty cpuPct = new SimpleDoubleProperty();
    private final LongProperty rssBytes = new SimpleLongProperty();

    ProcRow(long pid) {
        this.pid = pid;
    }

    public long pid() { return pid; }
    public StringProperty nameProperty() { return name; }
    public DoubleProperty cpuPctProperty() { return cpuPct; }
    public LongProperty rssBytesProperty() { return rssBytes; }

    void update(Message.ProcStats p) {
        name.set(p.name() == null ? "" : p.name());
        cpuPct.set(p.cpuPct());
        rssBytes.set(p.rssBytes());
    }
}
