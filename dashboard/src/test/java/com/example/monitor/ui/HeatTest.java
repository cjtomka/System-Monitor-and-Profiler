package com.example.monitor.ui;

import static org.junit.jupiter.api.Assertions.*;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

class HeatTest {

    @Test
    void movesFromGreenThroughYellowToRed() {
        assertEquals(120.0, Heat.color(0).getHue(), 0.5);
        assertEquals(60.0, Heat.color(50).getHue(), 0.5);
        assertEquals(0.0, Heat.color(100).getHue(), 0.5);
    }

    @Test
    void clampsOutOfRangeAndTreatsNaNAsIdle() {
        assertEquals(Heat.color(0), Heat.color(-20));
        assertEquals(Heat.color(100), Heat.color(250));
        assertEquals(Heat.color(0), Heat.color(Double.NaN));
        assertNotEquals(Heat.color(0), Color.TRANSPARENT);
    }
}
