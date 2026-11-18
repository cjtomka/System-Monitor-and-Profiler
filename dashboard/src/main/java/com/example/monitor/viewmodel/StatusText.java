package com.example.monitor.viewmodel;

import com.example.monitor.net.ConnectionState;

/** The words the UI shows for each connection state. Pure. */
public final class StatusText {
    private StatusText() {}

    /** For the status bar; `detail` carries the retry delay and reason in the disconnected states. */
    public static String statusLine(ConnectionState state, String detail) {
        String d = detail == null ? "" : detail.trim();
        return switch (state) {
            case CONNECTED        -> "Connected";
            case CONNECTING       -> "Connecting";
            case WAITING          -> d.isEmpty() ? "Disconnected" : "Disconnected: " + d;
            case VERSION_MISMATCH -> d.isEmpty() ? "Incompatible protocol" : "Incompatible protocol: " + d;
            case STOPPED          -> "Stopped";
        };
    }

    /** The banner drawn over a graph; empty means no banner. */
    public static String overlay(ConnectionState state, boolean hasData) {
        return switch (state) {
            case CONNECTED        -> hasData ? "" : "Waiting for data…";
            case CONNECTING       -> "Connecting…";
            case WAITING          -> hasData ? "Reconnecting…" : "Waiting for the daemon…";
            case VERSION_MISMATCH -> "The daemon speaks a different protocol version.";
            case STOPPED          -> "Disconnected.";
        };
    }
}
