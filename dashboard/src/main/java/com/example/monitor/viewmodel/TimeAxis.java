package com.example.monitor.viewmodel;

import java.util.ArrayList;
import java.util.List;

/** Tick positions and labels for the graph's time axis. Pure. */
public final class TimeAxis {
    private static final int[] STEPS = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1200, 1800, 3600};
    private static final int MAX_TICKS = 6;

    private TimeAxis() {}

    /** The smallest "nice" step that gives at most six intervals across the window. */
    public static int tickStepSeconds(double windowSeconds) {
        for (int step : STEPS) {
            if (windowSeconds / step <= MAX_TICKS) return step;
        }
        return STEPS[STEPS.length - 1];
    }

    /** Tick positions as "seconds ago": 0, step, 2*step, ... up to the window. */
    public static List<Double> ticks(double windowSeconds) {
        int step = tickStepSeconds(windowSeconds);
        List<Double> out = new ArrayList<>();
        for (long t = 0; t <= windowSeconds; t += step) out.add((double) t);
        return out;
    }

    public static String formatAgo(double secondsAgo) {
        long sec = Math.round(Math.max(0, secondsAgo));
        if (sec == 0) return "now";
        if (sec < 120) return "-" + sec + "s";
        long minutes = sec / 60, rest = sec % 60;
        return rest == 0 ? "-" + minutes + "m" : String.format("-%dm%02ds", minutes, rest);
    }
}
