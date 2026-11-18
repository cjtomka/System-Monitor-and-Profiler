package com.example.monitor.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.net.ConnectionState;
import org.junit.jupiter.api.Test;

class StatusTextTest {

    @Test
    void overlayDependsOnTheStateAndWhetherThereIsData() {
        assertEquals("Waiting for data…", StatusText.overlay(ConnectionState.CONNECTED, false));
        assertEquals("", StatusText.overlay(ConnectionState.CONNECTED, true));
        assertEquals("Connecting…", StatusText.overlay(ConnectionState.CONNECTING, true));
        assertEquals("Waiting for the daemon…", StatusText.overlay(ConnectionState.WAITING, false));
        assertEquals("Reconnecting…", StatusText.overlay(ConnectionState.WAITING, true));
        assertTrue(StatusText.overlay(ConnectionState.VERSION_MISMATCH, true).contains("protocol"));
        assertEquals("Disconnected.", StatusText.overlay(ConnectionState.STOPPED, true));
    }

    @Test
    void theStatusLineCarriesTheRetryDetailWhenDisconnected() {
        assertEquals("Connected", StatusText.statusLine(ConnectionState.CONNECTED, "connected to some\\path"));
        assertEquals("Disconnected: refused; retrying in 500 ms",
                StatusText.statusLine(ConnectionState.WAITING, "refused; retrying in 500 ms"));
        assertEquals("Disconnected", StatusText.statusLine(ConnectionState.WAITING, ""));
        assertEquals("Incompatible protocol: unsupported protocol version 2",
                StatusText.statusLine(ConnectionState.VERSION_MISMATCH, "unsupported protocol version 2"));
    }
}
