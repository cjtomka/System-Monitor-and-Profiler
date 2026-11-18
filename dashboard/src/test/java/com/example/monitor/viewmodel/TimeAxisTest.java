package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TimeAxisTest {

    @Test
    void theStepGivesAtMostSixIntervals() {
        assertEquals(1, TimeAxis.tickStepSeconds(0.5));
        assertEquals(1, TimeAxis.tickStepSeconds(5));
        assertEquals(10, TimeAxis.tickStepSeconds(60));
        assertEquals(60, TimeAxis.tickStepSeconds(300));
        assertEquals(120, TimeAxis.tickStepSeconds(600));
        assertEquals(1200, TimeAxis.tickStepSeconds(7200));
        assertEquals(3600, TimeAxis.tickStepSeconds(1_000_000));       // beyond the table: the largest step
    }

    @Test
    void ticksCountSecondsAgoFromNow() {
        assertEquals(List.of(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0), TimeAxis.ticks(60));
        assertEquals(List.of(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), TimeAxis.ticks(5));
        assertEquals(List.of(0.0, 10.0, 20.0, 30.0, 40.0), TimeAxis.ticks(45));
    }

    @Test
    void labelsReadAsSecondsOrMinutes() {
        assertEquals("now", TimeAxis.formatAgo(0));
        assertEquals("now", TimeAxis.formatAgo(-3));
        assertEquals("-10s", TimeAxis.formatAgo(10));
        assertEquals("-60s", TimeAxis.formatAgo(60));
        assertEquals("-90s", TimeAxis.formatAgo(90));
        assertEquals("-2m", TimeAxis.formatAgo(120));
        assertEquals("-2m30s", TimeAxis.formatAgo(150));
        assertEquals("-60m", TimeAxis.formatAgo(3600));
    }
}
