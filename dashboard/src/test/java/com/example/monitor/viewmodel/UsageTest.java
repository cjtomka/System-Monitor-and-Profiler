package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.protocol.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageTest {
    private static final long GIB = 1L << 30;

    private static Message.Snapshot snap(Message.MemStats mem, List<Message.DiskStats> disks) {
        return new Message.Snapshot(1, 1000, false, null, mem, disks, null, 0);
    }

    @Test
    void memoryRowsShowPhysicalAndCommit() {
        List<Usage.Row> rows = Usage.memory(snap(new Message.MemStats(16 * GIB, 4 * GIB, 8 * GIB, 32 * GIB), null));
        assertEquals(2, rows.size());

        assertEquals("Physical", rows.get(0).label());
        assertEquals(0.75, rows.get(0).fraction(), 1e-9);
        assertEquals("12.0 GiB of 16.0 GiB in use (75%)", rows.get(0).detail());

        assertEquals("Commit", rows.get(1).label());
        assertEquals(0.25, rows.get(1).fraction(), 1e-9);
        assertEquals("8.0 GiB of 32.0 GiB committed (25%)", rows.get(1).detail());
    }

    @Test
    void diskRowsAreOnePerDrive() {
        List<Usage.Row> rows = Usage.disks(snap(null, List.of(
                new Message.DiskStats("C:", 80 * GIB, 20 * GIB, 0, 0, 0),
                new Message.DiskStats("D:", 40 * GIB, 40 * GIB, 0, 0, 0))));
        assertEquals(2, rows.size());
        assertEquals("C:", rows.get(0).label());
        assertEquals(0.75, rows.get(0).fraction(), 1e-9);
        assertEquals("20.0 GiB free of 80.0 GiB", rows.get(0).detail());
        assertEquals(0.0, rows.get(1).fraction(), 1e-9);
        assertEquals("40.0 GiB free of 40.0 GiB", rows.get(1).detail());
    }

    @Test
    void missingSectionsGiveNoRows() {
        assertTrue(Usage.memory(null).isEmpty());
        assertTrue(Usage.memory(snap(null, null)).isEmpty());
        assertTrue(Usage.disks(null).isEmpty());
        assertTrue(Usage.disks(snap(null, null)).isEmpty());
    }

    @Test
    void zeroTotalsAndOddValuesStayInRange() {
        List<Usage.Row> mem = Usage.memory(snap(new Message.MemStats(0, 0, 5, 0), null));
        for (Usage.Row r : mem) assertEquals(0.0, r.fraction(), 1e-9);

        List<Usage.Row> odd = Usage.memory(snap(new Message.MemStats(10, 20, 50, 10), null));   // available > total, commit > limit
        assertEquals(0.0, odd.get(0).fraction(), 1e-9);
        assertEquals(1.0, odd.get(1).fraction(), 1e-9);                                          // clamped

        List<Usage.Row> disks = Usage.disks(snap(null, List.of(
                new Message.DiskStats("C:", 0, 0, 0, 0, 0), new Message.DiskStats("D:", 10, 20, 0, 0, 0))));
        for (Usage.Row r : disks) assertTrue(r.fraction() >= 0.0 && r.fraction() <= 1.0);
    }
}
