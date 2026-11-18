package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UnitsTest {
    private static final double GIB = 1024.0 * 1024.0 * 1024.0;

    @Test
    void formatsBytesWithBinaryUnits() {
        assertEquals("0 B", Units.bytes(0));
        assertEquals("1023 B", Units.bytes(1023));
        assertEquals("1.0 KiB", Units.bytes(1024));
        assertEquals("1.5 KiB", Units.bytes(1536));
        assertEquals("1.0 MiB", Units.bytes(1024.0 * 1024.0));
        assertEquals("16.0 GiB", Units.bytes(16 * GIB));
        assertEquals("393 MiB", Units.bytes(412_000_000));          // three digits: no decimal
    }

    @Test
    void formatsRates() {
        assertEquals("1.5 MiB/s", Units.rate(1.5 * 1024 * 1024));
        assertEquals("0 B/s", Units.rate(0));
    }

    @Test
    void scaledLabelsUseOneUnitAndTrimZeros() {
        assertEquals("0 MiB/s", Units.scaled(0, 2, true));
        assertEquals("0.25 MiB/s", Units.scaled(262_144, 2, true));
        assertEquals("1.5 GiB", Units.scaled(1.5 * GIB, 3, false));
        assertEquals("5 GiB", Units.scaled(5 * GIB, 3, false));
        assertEquals("100 B", Units.scaled(100, 0, false));          // zeros before the point are kept
    }

    @Test
    void unitIndexChangesAtPowersOf1024() {
        assertEquals(0, Units.unitIndex(1023));
        assertEquals(1, Units.unitIndex(1024));
        assertEquals(1, Units.unitIndex(1_048_575));
        assertEquals(2, Units.unitIndex(1_048_576));
        assertEquals(3, Units.unitIndex(GIB));
        assertEquals(4, Units.unitIndex(5e15));                       // capped at TiB
        assertEquals(1, Units.unitIndex(-2048));                      // sign is ignored
    }

    @Test
    void nonFiniteAndNegativeValuesAreSafe() {
        assertEquals("n/a", Units.bytes(Double.NaN));
        assertEquals("n/a", Units.rate(Double.POSITIVE_INFINITY));
        assertEquals("0 B", Units.bytes(-5));
        assertEquals("37.5%", Units.percent(37.5));
        assertEquals("n/a", Units.percent(Double.NaN));
    }
}
