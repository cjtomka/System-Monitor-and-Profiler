package com.example.monitor.viewmodel;

import com.example.monitor.protocol.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the usage bars show for memory and disks. Pure. */
public final class Usage {
    private Usage() {}

    /** fraction is 0..1 (how full); detail is the text shown beside the bar. */
    public record Row(String label, double fraction, String detail) {}

    public static List<Row> memory(Message.Snapshot s) {
        if (s == null || s.mem() == null) return List.of();
        Message.MemStats m = s.mem();
        long used = Math.max(0L, m.totalBytes() - m.availBytes());

        List<Row> rows = new ArrayList<>(2);
        rows.add(new Row("Physical", fraction(used, m.totalBytes()),
                String.format(Locale.ROOT, "%s of %s in use (%d%%)",
                        Units.bytes(used), Units.bytes(m.totalBytes()), percent(used, m.totalBytes()))));
        rows.add(new Row("Commit", fraction(m.commitBytes(), m.commitLimitBytes()),
                String.format(Locale.ROOT, "%s of %s committed (%d%%)",
                        Units.bytes(m.commitBytes()), Units.bytes(m.commitLimitBytes()),
                        percent(m.commitBytes(), m.commitLimitBytes()))));
        return rows;
    }

    public static List<Row> disks(Message.Snapshot s) {
        if (s == null || s.disks() == null) return List.of();
        List<Row> rows = new ArrayList<>();
        for (Message.DiskStats d : s.disks()) {
            long used = Math.max(0L, d.totalBytes() - d.freeBytes());
            rows.add(new Row(d.name(), fraction(used, d.totalBytes()),
                    String.format(Locale.ROOT, "%s free of %s", Units.bytes(d.freeBytes()), Units.bytes(d.totalBytes()))));
        }
        return rows;
    }

    static double fraction(long part, long whole) {
        if (whole <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, (double) part / whole));
    }

    private static int percent(long part, long whole) {
        return (int) Math.round(fraction(part, whole) * 100.0);
    }
}
