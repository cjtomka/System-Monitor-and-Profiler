package com.example.monitor.viewmodel;

import com.example.monitor.protocol.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Turns the snapshot history into lines a graph can draw. Pure: no JavaFX, easy to test. */
public final class ChartSeries {
    private ChartSeries() {}

    /** x is seconds relative to the newest snapshot (zero or negative). */
    public record Point(double x, double y) {}

    public record Stats(double latest, double average, double max, int samples) {
        static final Stats NONE = new Stats(Double.NaN, Double.NaN, Double.NaN, 0);
    }

    /** Disjoint runs of points: the line is broken wherever data is missing or time goes backwards. */
    public record Line(String name, List<List<Point>> segments, Stats stats) {
        public boolean isEmpty() {
            return stats.samples() == 0;
        }
    }

    public record Chart(List<Line> lines, double windowSeconds) {
        public boolean isEmpty() {
            for (Line l : lines) if (!l.isEmpty()) return false;
            return true;
        }

        /** The largest value on any line, or 0 when there is no data. */
        public double maxValue() {
            double m = 0.0;
            for (Line l : lines) if (!l.isEmpty()) m = Math.max(m, l.stats().max());
            return m;
        }
    }

    /** Reads one value per snapshot; null or a non-finite number means "no data for this snapshot". */
    public record Extractor(String name, Function<Message.Snapshot, Double> value) {}

    /**
     * @param history            oldest first, as MetricsModel.history() provides it
     * @param windowSeconds      how much time to show; zero or less means the whole buffer
     * @param maxPointsPerSegment above this a segment is decimated, keeping each bucket's peak
     */
    public static Chart build(List<Message.Snapshot> history, double windowSeconds,
                              int maxPointsPerSegment, List<Extractor> extractors) {
        if (maxPointsPerSegment < 2) throw new IllegalArgumentException("maxPointsPerSegment must be at least 2");

        if (history.isEmpty()) {
            List<Line> empty = new ArrayList<>(extractors.size());
            for (Extractor ex : extractors) empty.add(new Line(ex.name(), List.of(), Stats.NONE));
            return new Chart(Collections.unmodifiableList(empty), windowSeconds > 0 ? windowSeconds : 60.0);
        }

        long newestTs = history.get(history.size() - 1).tsMs();
        long oldestTs = history.get(0).tsMs();
        double window = windowSeconds > 0 ? windowSeconds : Math.max((newestTs - oldestTs) / 1000.0, 1.0);

        List<Line> lines = new ArrayList<>(extractors.size());
        for (Extractor ex : extractors) lines.add(buildLine(ex, history, newestTs, window, maxPointsPerSegment));
        return new Chart(Collections.unmodifiableList(lines), window);
    }

    private static Line buildLine(Extractor ex, List<Message.Snapshot> history, long newestTs,
                                  double window, int maxPoints) {
        List<List<Point>> segments = new ArrayList<>();
        List<Point> current = new ArrayList<>();
        long prevTs = Long.MIN_VALUE;
        double sum = 0, max = Double.NEGATIVE_INFINITY, latest = Double.NaN;
        int samples = 0;

        for (Message.Snapshot s : history) {
            double x = (s.tsMs() - newestTs) / 1000.0;
            if (x < -window) continue;                                  // older than the window

            Double v = ex.value().apply(s);
            if (v == null || !Double.isFinite(v)) {                     // warming up, or the collector failed
                flush(current, segments);
                current = new ArrayList<>();
                prevTs = Long.MIN_VALUE;
                continue;
            }
            if (s.tsMs() < prevTs) {                                    // the clock went backwards
                flush(current, segments);
                current = new ArrayList<>();
            }
            current.add(new Point(x, v));
            prevTs = s.tsMs();

            sum += v;
            max = Math.max(max, v);
            latest = v;
            samples++;
        }
        flush(current, segments);

        List<List<Point>> out = new ArrayList<>(segments.size());
        for (List<Point> seg : segments) out.add(decimate(seg, maxPoints));

        Stats stats = samples == 0 ? Stats.NONE : new Stats(latest, sum / samples, max, samples);
        return new Line(ex.name(), Collections.unmodifiableList(out), stats);
    }

    private static void flush(List<Point> segment, List<List<Point>> into) {
        if (!segment.isEmpty()) into.add(segment);
    }

    /** At most `max` points, one per bucket, each the highest of its bucket, so spikes survive. */
    static List<Point> decimate(List<Point> pts, int max) {
        int n = pts.size();
        if (n <= max) return pts;
        List<Point> out = new ArrayList<>(max);
        for (int b = 0; b < max; b++) {
            int from = (int) ((long) b * n / max);
            int to = (int) ((long) (b + 1) * n / max);
            Point best = pts.get(from);
            for (int i = from + 1; i < to; i++) {
                if (pts.get(i).y() > best.y()) best = pts.get(i);
            }
            out.add(best);
        }
        return out;
    }
}