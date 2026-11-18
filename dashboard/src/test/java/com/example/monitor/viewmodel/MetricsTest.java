package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.protocol.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsTest {
    private static final long GIB = 1L << 30;

    private static Message.Snapshot withCpu(double pct) {
        return new Message.Snapshot(1, 1000, false, new Message.CpuStats(pct, List.of()), null, null, null, 0);
    }

    private static Message.Snapshot with(Message.MemStats mem, List<Message.DiskStats> disks) {
        return new Message.Snapshot(1, 1000, false, null, mem, disks, null, 0);
    }

    @Test
    void cpuTotalIsClampedAndMissingWhenAbsent() {
        ChartSeries.Extractor ex = Metrics.cpuTotal();
        assertEquals(37.5, ex.value().apply(withCpu(37.5)), 1e-9);
        assertEquals(100.0, ex.value().apply(withCpu(150.0)), 1e-9);
        assertEquals(0.0, ex.value().apply(withCpu(-5.0)), 1e-9);
        assertNull(ex.value().apply(withCpu(Double.NaN)));
        assertNull(ex.value().apply(with(null, null)));
    }

    @Test
    void memoryExtractorsReadTheMemorySection() {
        Message.Snapshot s = with(new Message.MemStats(16 * GIB, 4 * GIB, 8 * GIB, 32 * GIB), null);
        assertEquals(12.0 * GIB, Metrics.memInUse().value().apply(s), 1e-6);
        assertEquals(8.0 * GIB, Metrics.memCommit().value().apply(s), 1e-6);

        Message.Snapshot odd = with(new Message.MemStats(10, 20, 0, 0), null);        // available above total
        assertEquals(0.0, Metrics.memInUse().value().apply(odd), 1e-9);

        assertNull(Metrics.memInUse().value().apply(with(null, null)));
        assertNull(Metrics.memCommit().value().apply(with(null, null)));
    }

    @Test
    void diskExtractorsFindTheDriveByName() {
        Message.Snapshot s = with(null, List.of(
                new Message.DiskStats("C:", 100, 50, 111, 222, 0.5),
                new Message.DiskStats("D:", 100, 50, 333, 444, 0.1)));
        assertEquals(111.0, Metrics.diskRead("C:").value().apply(s), 1e-9);
        assertEquals(444.0, Metrics.diskWrite("d:").value().apply(s), 1e-9);         // case-insensitive
        assertNull(Metrics.diskRead("E:").value().apply(s));                          // no such drive
        assertNull(Metrics.diskRead("C:").value().apply(with(null, null)));           // disks section missing
        assertNull(Metrics.diskRead(null).value().apply(s));
        assertEquals("D:", Metrics.findDisk(s, "D:").name());
    }

    @Test
    void driveNamesAndNewestHandleMissingData() {
        Message.Snapshot s = with(null, List.of(
                new Message.DiskStats("C:", 1, 1, 0, 0, 0), new Message.DiskStats("D:", 1, 1, 0, 0, 0)));
        assertEquals(List.of("C:", "D:"), Metrics.driveNames(s));
        assertTrue(Metrics.driveNames(null).isEmpty());
        assertTrue(Metrics.driveNames(with(null, null)).isEmpty());

        assertNull(Metrics.newest(List.of()));
        Message.Snapshot a = withCpu(1), b = withCpu(2);
        assertSame(b, Metrics.newest(List.of(a, b)));
    }
}
