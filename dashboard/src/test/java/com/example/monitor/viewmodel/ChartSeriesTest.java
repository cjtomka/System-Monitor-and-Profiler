package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.protocol.Message;
import com.example.monitor.viewmodel.ChartSeries.Chart;
import com.example.monitor.viewmodel.ChartSeries.Extractor;
import com.example.monitor.viewmodel.ChartSeries.Point;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartSeriesTest {
    private static final Extractor CPU = new Extractor("cpu",
            s -> s.cpu() == null ? null : s.cpu().totalPct());
    private static final Extractor AVAIL = new Extractor("avail",
            s -> s.mem() == null ? null : (double) s.mem().availBytes());

    private static Message.Snapshot s(long seq, long tsMs, Double pct) {
        return sm(seq, tsMs, pct, null);
    }

    private static Message.Snapshot sm(long seq, long tsMs, Double pct, Long avail) {
        Message.CpuStats cpu = pct == null ? null : new Message.CpuStats(pct, List.of(pct));
        Message.MemStats mem = avail == null ? null : new Message.MemStats(100, avail, 0, 0);
        return new Message.Snapshot(seq, tsMs, false, cpu, mem, null, null, 0);
    }

    private static Chart one(List<Message.Snapshot> history, double window, int maxPoints) {
        return ChartSeries.build(history, window, maxPoints, List.of(CPU));
    }

    @Test
    void positionsPointsRelativeToTheNewestSnapshot() {
        Chart c = one(List.of(s(1, 1000, 10.0), s(2, 2000, 20.0), s(3, 3000, 30.0)), 60, 100);
        List<List<Point>> segs = c.lines().get(0).segments();
        assertEquals(1, segs.size());
        List<Point> pts = segs.get(0);
        assertEquals(-2.0, pts.get(0).x(), 1e-9);
        assertEquals(10.0, pts.get(0).y(), 1e-9);
        assertEquals(0.0, pts.get(2).x(), 1e-9);
        assertEquals(60.0, c.windowSeconds(), 1e-9);
        assertEquals("cpu", c.lines().get(0).name());
    }

    @Test
    void theWindowKeepsOnlyRecentPoints() {
        List<Message.Snapshot> h = new ArrayList<>();
        for (int i = 0; i <= 5; i++) h.add(s(i + 1, i * 1000L, 10.0 + i));
        Chart c = one(h, 2, 100);
        List<Point> pts = c.lines().get(0).segments().get(0);
        assertEquals(3, pts.size());                                  // x = -2, -1, 0
        assertEquals(-2.0, pts.get(0).x(), 1e-9);
        assertEquals(3, c.lines().get(0).stats().samples());
    }

    @Test
    void aZeroWindowMeansTheWholeSpan() {
        List<Message.Snapshot> h = new ArrayList<>();
        for (int i = 0; i <= 5; i++) h.add(s(i + 1, i * 1000L, 10.0));
        Chart c = one(h, 0, 100);
        assertEquals(6, c.lines().get(0).segments().get(0).size());
        assertEquals(5.0, c.windowSeconds(), 1e-9);
    }

    @Test
    void missingValuesSplitTheLineAndAreExcludedFromStats() {
        Chart c = one(List.of(s(1, 1000, 10.0), s(2, 2000, null), s(3, 3000, 30.0), s(4, 4000, 50.0)), 60, 100);
        List<List<Point>> segs = c.lines().get(0).segments();
        assertEquals(2, segs.size());
        assertEquals(1, segs.get(0).size());
        assertEquals(2, segs.get(1).size());
        assertEquals(3, c.lines().get(0).stats().samples());
        assertEquals(30.0, c.lines().get(0).stats().average(), 1e-9);
    }

    @Test
    void aClockGoingBackwardsStartsANewSegment() {
        Chart c = one(List.of(s(1, 1000, 10.0), s(2, 2000, 20.0), s(3, 1500, 30.0), s(4, 2500, 40.0)), 60, 100);
        List<List<Point>> segs = c.lines().get(0).segments();
        assertEquals(2, segs.size());
        assertEquals(2, segs.get(0).size());
        assertEquals(2, segs.get(1).size());
        assertEquals(-1.0, segs.get(1).get(0).x(), 1e-9);             // ts 1500 against the newest, 2500
    }

    @Test
    void statsAreLatestAverageAndMax() {
        Chart c = one(List.of(s(1, 1000, 10.0), s(2, 2000, 20.0), s(3, 3000, 60.0)), 60, 100);
        ChartSeries.Stats st = c.lines().get(0).stats();
        assertEquals(60.0, st.latest(), 1e-9);
        assertEquals(30.0, st.average(), 1e-9);
        assertEquals(60.0, st.max(), 1e-9);
        assertEquals(3, st.samples());
        assertFalse(c.isEmpty());
    }

    @Test
    void nonFiniteValuesAreTreatedAsMissing() {
        Chart c = one(List.of(s(1, 1000, 10.0), s(2, 2000, Double.NaN),
                s(3, 3000, Double.POSITIVE_INFINITY), s(4, 4000, 30.0)), 60, 100);
        assertEquals(2, c.lines().get(0).segments().size());
        assertEquals(2, c.lines().get(0).stats().samples());
    }

    @Test
    void anEmptyHistoryStillKeepsTheWindowAndTheLineNames() {
        Chart c = one(List.of(), 300, 100);
        assertEquals(1, c.lines().size());
        assertEquals("cpu", c.lines().get(0).name());
        assertTrue(c.isEmpty());
        assertEquals(300.0, c.windowSeconds(), 1e-9);
        assertEquals(60.0, one(List.of(), 0, 100).windowSeconds(), 1e-9);
    }

    @Test
    void decimationKeepsPeaksAndBoundsTheSize() {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) pts.add(new Point(i / 10.0, i == 7321 ? 99.0 : 5.0));

        List<Point> out = ChartSeries.decimate(pts, 100);
        assertEquals(100, out.size());
        assertTrue(out.stream().anyMatch(p -> p.y() == 99.0), "the spike must survive");
        for (int i = 1; i < out.size(); i++) assertTrue(out.get(i).x() > out.get(i - 1).x());

        List<Point> small = pts.subList(0, 50);
        assertSame(small, ChartSeries.decimate(small, 100));          // under the limit: returned as is
    }

    @Test
    void severalLinesShareOneTimeBaseButHaveTheirOwnGaps() {
        List<Message.Snapshot> h = List.of(
                sm(1, 1000, 10.0, 50L), sm(2, 2000, 20.0, null), sm(3, 3000, 30.0, 70L));
        Chart c = ChartSeries.build(h, 60, 100, List.of(CPU, AVAIL));

        assertEquals(2, c.lines().size());
        assertEquals(1, c.lines().get(0).segments().size());
        assertEquals(3, c.lines().get(0).segments().get(0).size());

        List<List<Point>> avail = c.lines().get(1).segments();
        assertEquals(2, avail.size());                                // the gap at seq 2 splits it
        assertEquals(-2.0, avail.get(0).get(0).x(), 1e-9);
        assertEquals(0.0, avail.get(1).get(0).x(), 1e-9);
        assertEquals(60.0, c.windowSeconds(), 1e-9);
    }

    @Test
    void maxValueIsTheLargestAcrossAllLines() {
        List<Message.Snapshot> h = List.of(sm(1, 1000, 10.0, 50L), sm(2, 2000, 30.0, 70L));
        Chart c = ChartSeries.build(h, 60, 100, List.of(CPU, AVAIL));
        assertEquals(70.0, c.maxValue(), 1e-9);
        assertEquals(0.0, ChartSeries.build(List.of(), 60, 100, List.of(CPU)).maxValue(), 1e-9);
    }
}
