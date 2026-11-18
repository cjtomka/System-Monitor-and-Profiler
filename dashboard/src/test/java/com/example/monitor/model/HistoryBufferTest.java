package com.example.monitor.model;

import static com.example.monitor.TestData.hist;
import static com.example.monitor.TestData.live;
import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.protocol.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryBufferTest {

    private static List<Long> seqs(HistoryBuffer b) {
        return b.toList().stream().map(Message.Snapshot::seq).toList();
    }

    @Test
    void keepsEntriesSortedWhateverTheArrivalOrder() {
        HistoryBuffer b = new HistoryBuffer(100);
        b.addLive(live(5));
        b.addHistory(List.of(hist(1), hist(2), hist(3)));       // the history chunk arrives after a live snapshot
        b.addLive(live(6));
        b.addHistory(List.of(hist(4)));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), seqs(b));
    }

    @Test
    void liveWinsOverHistoryForTheSameSeq() {
        HistoryBuffer b = new HistoryBuffer(10);
        b.addHistory(List.of(hist(3)));
        b.addLive(live(3));                                      // live replaces history
        assertNotNull(b.toList().get(0).topProcs());

        b.addLive(live(4));
        b.addHistory(List.of(hist(4)));                          // history must not replace live
        assertNotNull(b.toList().get(1).topProcs());
        assertEquals(2, b.size());
    }

    @Test
    void evictsTheOldestBeyondCapacity() {
        HistoryBuffer b = new HistoryBuffer(3);
        for (long i = 1; i <= 5; i++) b.addLive(live(i));
        assertEquals(List.of(3L, 4L, 5L), seqs(b));
    }

    @Test
    void oldHistoryThatDoesNotFitIsDropped() {
        HistoryBuffer b = new HistoryBuffer(3);
        b.addLive(live(10));
        b.addLive(live(11));
        b.addLive(live(12));
        b.addHistory(List.of(hist(1), hist(2)));
        assertEquals(List.of(10L, 11L, 12L), seqs(b));
    }

    @Test
    void shrinkingTheCapacityEvictsAndZeroIsRejected() {
        HistoryBuffer b = new HistoryBuffer(5);
        for (long i = 1; i <= 5; i++) b.addLive(live(i));
        b.setCapacity(2);
        assertEquals(List.of(4L, 5L), seqs(b));
        assertThrows(IllegalArgumentException.class, () -> b.setCapacity(0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryBuffer(0));
    }

    @Test
    void clearEmptiesTheBufferAndBoundsReportMinusOne() {
        HistoryBuffer b = new HistoryBuffer(5);
        assertEquals(-1, b.oldestSeq());
        assertEquals(-1, b.newestSeq());
        b.addLive(live(7));
        b.addLive(live(9));
        assertEquals(7, b.oldestSeq());
        assertEquals(9, b.newestSeq());
        b.clear();
        assertEquals(0, b.size());
        assertEquals(-1, b.newestSeq());
    }
}
