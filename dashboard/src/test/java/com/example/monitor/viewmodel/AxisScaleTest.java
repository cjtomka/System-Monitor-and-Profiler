package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.viewmodel.AxisScale.YAxis;
import java.util.List;
import org.junit.jupiter.api.Test;

class AxisScaleTest {
    private static final double MIB = 1024.0 * 1024.0;
    private static final double GIB = 1024.0 * MIB;

    @Test
    void fixedPercentAxisHasQuarterTicks() {
        YAxis a = AxisScale.fixed(100, 0);
        assertEquals(0.0, a.min());
        assertEquals(100.0, a.max());
        assertEquals(List.of(0.0, 25.0, 50.0, 75.0, 100.0), a.ticks());
        assertEquals(0, a.unitIndex());
    }

    @Test
    void fixedMemoryAxisUsesNiceTicksAndKeepsTheExactMax() {
        YAxis a = AxisScale.fixed(16 * GIB, 3);
        assertEquals(16 * GIB, a.max(), 1e-6);
        assertEquals(3, a.unitIndex());
        assertEquals(4, a.ticks().size());                            // 0, 5, 10, 15 GiB: the top is not labeled
        assertEquals(15 * GIB, a.ticks().get(3), 1e-6);
    }

    @Test
    void autoAxisNeverGoesBelowTheMinimumTop() {
        YAxis a = AxisScale.auto(0, MIB);
        assertEquals(MIB, a.max(), 1e-6);
        assertEquals(2, a.unitIndex());
        assertEquals(5, a.ticks().size());                            // 0, 0.25, 0.5, 0.75, 1 MiB
        assertEquals(0.25 * MIB, a.ticks().get(1), 1e-6);
    }

    @Test
    void autoAxisCoversTheDataWithHeadroom() {
        YAxis a = AxisScale.auto(3 * MIB, MIB);
        assertEquals(4 * MIB, a.max(), 1e-6);
        assertTrue(a.max() >= 3 * MIB * 1.05);
        for (int i = 1; i < a.ticks().size(); i++) assertTrue(a.ticks().get(i) > a.ticks().get(i - 1));
        assertEquals(a.max(), a.ticks().get(a.ticks().size() - 1), 1e-6);
    }

    @Test
    void autoAxisPicksTheUnitFromTheData() {
        YAxis a = AxisScale.auto(5 * GIB, MIB);
        assertEquals(3, a.unitIndex());
        assertEquals(6 * GIB, a.max(), 1e-6);
        assertEquals(4, a.ticks().size());                            // 0, 2, 4, 6 GiB
    }

    @Test
    void nonFiniteDataFallsBackToTheMinimum() {
        YAxis a = AxisScale.auto(Double.NaN, 2048);
        assertEquals(2048.0, a.max(), 1e-6);
        assertEquals(1, a.unitIndex());

        YAxis b = AxisScale.auto(0, 0);                               // nothing to go on at all
        assertEquals(1.0, b.max(), 1e-9);
        assertEquals(0, b.unitIndex());
    }
}
