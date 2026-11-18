package com.example.monitor.viewmodel;

import com.example.monitor.protocol.Message;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * The process table's rows, kept in step with the daemon's list by PID. Existing rows are
 * updated in place, so a table keeps its selection and scroll position, and (because the list
 * reports property changes as "updated") a SortedList over it re-sorts when values change.
 * FX thread only.
 */
public final class ProcRows {
    private final ObservableList<ProcRow> rows = FXCollections.observableArrayList(
            r -> new Observable[] {r.nameProperty(), r.cpuPctProperty(), r.rssBytesProperty()});
    private final ObservableList<ProcRow> view = FXCollections.unmodifiableObservableList(rows);

    public ObservableList<ProcRow> rows() {
        return view;
    }

    /** null or empty clears the table. The first entry wins if a PID appears twice. */
    public void sync(List<Message.ProcStats> procs) {
        if (procs == null || procs.isEmpty()) {
            if (!rows.isEmpty()) rows.clear();
            return;
        }
        Map<Long, Message.ProcStats> incoming = new LinkedHashMap<>();
        for (Message.ProcStats p : procs) incoming.putIfAbsent(p.pid(), p);

        rows.removeIf(r -> !incoming.containsKey(r.pid()));

        Set<Long> have = new HashSet<>();
        for (ProcRow r : rows) {
            r.update(incoming.get(r.pid()));
            have.add(r.pid());
        }
        for (Map.Entry<Long, Message.ProcStats> e : incoming.entrySet()) {
            if (have.contains(e.getKey())) continue;
            ProcRow r = new ProcRow(e.getKey());
            r.update(e.getValue());
            rows.add(r);
        }
    }
}
