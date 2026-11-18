package com.example.monitor.model;

import com.example.monitor.protocol.Message.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Snapshots keyed by seq: sorted, de-duplicated and bounded. Not thread-safe (FX thread only).
 *
 * Live snapshots and history chunks interleave and can arrive out of order, so insertion is
 * by seq. When the same seq arrives twice, the live copy wins: it carries the process list
 * and drop counter that history entries leave out.
 */
public final class HistoryBuffer {
    private final TreeMap<Long, Snapshot> bySeq = new TreeMap<>();
    private int capacity;

    public HistoryBuffer(int capacity) {
        setCapacity(capacity);
    }

    public void setCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1");
        this.capacity = capacity;
        evict();
    }

    public int capacity() {
        return capacity;
    }

    public void addLive(Snapshot s) {
        bySeq.put(s.seq(), s);                 // replaces a history entry with the same seq
        evict();
    }

    public void addHistory(List<Snapshot> entries) {
        for (Snapshot s : entries) bySeq.putIfAbsent(s.seq(), s);   // never replaces a live entry
        evict();
    }

    public void clear() {
        bySeq.clear();
    }

    public int size() {
        return bySeq.size();
    }

    /** Oldest-first copy. */
    public List<Snapshot> toList() {
        return new ArrayList<>(bySeq.values());
    }

    public long oldestSeq() {
        return bySeq.isEmpty() ? -1 : bySeq.firstKey();
    }

    public long newestSeq() {
        return bySeq.isEmpty() ? -1 : bySeq.lastKey();
    }

    private void evict() {
        while (bySeq.size() > capacity) bySeq.pollFirstEntry();
    }
}
