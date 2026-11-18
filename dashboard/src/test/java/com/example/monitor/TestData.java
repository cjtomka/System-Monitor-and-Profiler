package com.example.monitor;

import com.example.monitor.protocol.Message;
import java.util.ArrayList;
import java.util.List;

public final class TestData {
    private TestData() {}

    /** A live snapshot: carries a (here empty) process list. */
    public static Message.Snapshot live(long seq) {
        return liveDropped(seq, 0);
    }

    public static Message.Snapshot liveDropped(long seq, long dropped) {
        return new Message.Snapshot(seq, seq * 1000, false,
                new Message.CpuStats(10.0, List.of(10.0)), null, null, List.of(), dropped);
    }

    /** A history entry: no process list, no dropped counter. */
    public static Message.Snapshot hist(long seq) {
        return new Message.Snapshot(seq, seq * 1000, false, null, null, null, null, 0);
    }

    public static Message.Hello hello(int intervalMs, int capacity) {
        return new Message.Hello("fake", 8, intervalMs, capacity);
    }

    public static Message.History history(boolean last, long... seqs) {
        List<Message.Snapshot> list = new ArrayList<>();
        for (long s : seqs) list.add(hist(s));
        return new Message.History(list.size(), last, list);
    }
}