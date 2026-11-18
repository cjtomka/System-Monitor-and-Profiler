package com.example.monitor.viewmodel;

import java.util.ArrayList;
import java.util.List;

/** Y-axis ranges and tick positions. Pure. */
public final class AxisScale {
    private static final int TARGET_INTERVALS = 4;

    private AxisScale() {}

    /** The axis runs from min to max; ticks are positions in the same units as the data. */
    public record YAxis(double min, double max, List<Double> ticks, int unitIndex) {}

    /** A "nice" step (1, 2, 2.5, 5 or 10 times a power of ten) giving about `target` intervals. */
    static double niceStep(double range, int target) {
        if (!(range > 0) || !Double.isFinite(range)) return 1;
        double raw = range / target;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / mag;
        double nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
        return nice * mag;
    }

    /**
     * An axis from 0 to exactly `max` (percent: 0..100, memory: 0..total RAM), with nice ticks
     * that may stop short of the top. unitIndex is the display unit (see Units); use 0 for percent.
     */
    public static YAxis fixed(double max, int unitIndex) {
        double top = max > 0 && Double.isFinite(max) ? max : 1.0;
        int u = Math.max(0, Math.min(4, unitIndex));
        double base = Units.base(u);
        double step = niceStep(top / base, TARGET_INTERVALS) * base;
        List<Double> ticks = new ArrayList<>();
        for (int k = 0; k * step <= top * (1 + 1e-9); k++) ticks.add(k * step);
        return new YAxis(0, top, List.copyOf(ticks), u);
    }

    /**
     * An axis from 0 to a nice top that covers the data (plus 5% headroom) and is never below
     * minTop, so an idle signal does not stretch noise into a full-height line. The unit is
     * chosen from the data, for byte-valued data.
     */
    public static YAxis auto(double dataMax, double minTop) {
        double floor = Double.isFinite(minTop) && minTop > 0 ? minTop : 0.0;
        double want = Math.max(Double.isFinite(dataMax) ? dataMax * 1.05 : 0.0, floor);
        if (!(want > 0)) want = 1.0;

        int u = Units.unitIndex(want);
        double base = Units.base(u);
        double step = niceStep(want / base, TARGET_INTERVALS);          // in units of `base`
        double top = Math.ceil(want / base / step - 1e-9) * step;       // also in units of `base`

        List<Double> ticks = new ArrayList<>();
        for (int k = 0; k * step <= top * (1 + 1e-9); k++) ticks.add(k * step * base);
        return new YAxis(0, top * base, List.copyOf(ticks), u);
    }
}
