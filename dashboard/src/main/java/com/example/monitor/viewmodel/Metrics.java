package com.example.monitor.viewmodel;

import com.example.monitor.protocol.Message;
import java.util.ArrayList;
import java.util.List;

/** Extractors for ChartSeries, plus small lookups on snapshots. Pure. */
public final class Metrics {
    private Metrics() {}

    /** Total CPU load, clamped to 0..100; missing while the CPU section is absent or not a number. */
    public static ChartSeries.Extractor cpuTotal() {
        return new ChartSeries.Extractor("CPU", s -> {
            Message.CpuStats c = s.cpu();
            if (c == null || Double.isNaN(c.totalPct())) return null;
            return Math.max(0.0, Math.min(100.0, c.totalPct()));
        });
    }

    /** Physical memory in use, in bytes (total minus available, never negative). */
    public static ChartSeries.Extractor memInUse() {
        return new ChartSeries.Extractor("In use", s -> {
            Message.MemStats m = s.mem();
            if (m == null) return null;
            return (double) Math.max(0L, m.totalBytes() - m.availBytes());
        });
    }

    public static ChartSeries.Extractor memCommit() {
        return new ChartSeries.Extractor("Committed", s -> {
            Message.MemStats m = s.mem();
            if (m == null) return null;
            return (double) m.commitBytes();
        });
    }

    public static ChartSeries.Extractor diskRead(String drive) {
        return new ChartSeries.Extractor("Read", s -> {
            Message.DiskStats d = findDisk(s, drive);
            if (d == null) return null;
            return (double) d.readBps();
        });
    }

    public static ChartSeries.Extractor diskWrite(String drive) {
        return new ChartSeries.Extractor("Write", s -> {
            Message.DiskStats d = findDisk(s, drive);
            if (d == null) return null;
            return (double) d.writeBps();
        });
    }

    /** The drive's entry in this snapshot (case-insensitive), or null. */
    public static Message.DiskStats findDisk(Message.Snapshot s, String drive) {
        if (s == null || s.disks() == null || drive == null) return null;
        for (Message.DiskStats d : s.disks()) {
            if (drive.equalsIgnoreCase(d.name())) return d;
        }
        return null;
    }

    public static List<String> driveNames(Message.Snapshot s) {
        List<String> names = new ArrayList<>();
        if (s != null && s.disks() != null) {
            for (Message.DiskStats d : s.disks()) names.add(d.name());
        }
        return names;
    }

    /** The newest snapshot of an oldest-first history, or null when it is empty. */
    public static Message.Snapshot newest(List<Message.Snapshot> history) {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }
}
