package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.protocol.Message;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.Test;

class ProcRowsTest {
    private final ProcRows rows = new ProcRows();

    private static Message.ProcStats p(long pid, String name, double cpu, long rss) {
        return new Message.ProcStats(pid, name, cpu, rss);
    }

    private ProcRow row(long pid) {
        return rows.rows().stream().filter(r -> r.pid() == pid).findFirst().orElseThrow();
    }

    @Test
    void addsRowsForNewProcesses() {
        rows.sync(List.of(p(4, "System", 0.5, 100), p(4120, "chrome.exe", 12.1, 412_000_000)));
        assertEquals(2, rows.rows().size());
        assertEquals("chrome.exe", row(4120).nameProperty().get());
        assertEquals(12.1, row(4120).cpuPctProperty().get(), 1e-9);
        assertEquals(412_000_000L, row(4120).rssBytesProperty().get());
    }

    @Test
    void updatesExistingRowsInPlaceAndReportsThemAsUpdated() {
        rows.sync(List.of(p(10, "a.exe", 1.0, 1000), p(11, "b.exe", 2.0, 2000)));
        ProcRow before = row(10);

        List<Boolean> updates = new ArrayList<>();
        rows.rows().addListener((ListChangeListener<ProcRow>) c -> {
            while (c.next()) updates.add(c.wasUpdated());
        });
        rows.sync(List.of(p(10, "a.exe", 55.0, 3000), p(11, "b.exe", 2.0, 2000)));

        assertSame(before, row(10));                                   // the same object: selection survives
        assertEquals(55.0, row(10).cpuPctProperty().get(), 1e-9);
        assertEquals(3000L, row(10).rssBytesProperty().get());
        assertTrue(updates.contains(true), "a SortedList needs update events to re-sort");
        assertEquals(2, rows.rows().size());
    }

    @Test
    void removesRowsThatDisappeared() {
        rows.sync(List.of(p(1, "a", 1, 1), p(2, "b", 2, 2), p(3, "c", 3, 3)));
        rows.sync(List.of(p(2, "b", 2, 2)));
        assertEquals(1, rows.rows().size());
        assertEquals(2, rows.rows().get(0).pid());
    }

    @Test
    void nullOrEmptyClearsTheTable() {
        rows.sync(List.of(p(1, "a", 1, 1)));
        rows.sync(null);
        assertTrue(rows.rows().isEmpty());

        rows.sync(List.of(p(1, "a", 1, 1)));
        rows.sync(List.of());
        assertTrue(rows.rows().isEmpty());
    }

    @Test
    void aDuplicatePidKeepsTheFirstEntry() {
        rows.sync(List.of(p(7, "first.exe", 1, 1), p(7, "second.exe", 2, 2)));
        assertEquals(1, rows.rows().size());
        assertEquals("first.exe", row(7).nameProperty().get());
    }
}
