package com.example.monitor.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReconnectPolicyTest {

    @Test
    void backsOffExponentiallyUpToTheCap() {
        ReconnectPolicy p = new ReconnectPolicy(500, 5000);
        long[] expected = {500, 1000, 2000, 4000, 5000, 5000};
        for (long e : expected) assertEquals(e, p.nextDelayMs());
    }

    @Test
    void resetStartsOver() {
        ReconnectPolicy p = new ReconnectPolicy(500, 5000);
        p.nextDelayMs();
        p.nextDelayMs();
        p.reset();
        assertEquals(500, p.nextDelayMs());
    }

    @Test
    void rejectsNonsenseBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ReconnectPolicy(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new ReconnectPolicy(500, 100));
    }
}
