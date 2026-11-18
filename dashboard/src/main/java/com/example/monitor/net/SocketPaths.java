package com.example.monitor.net;

import java.nio.file.Path;

public final class SocketPaths {
    private SocketPaths() {}

    /** The same default the daemon uses: %LOCALAPPDATA%\monitord\monitor.sock */
    public static Path defaultPath() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("LOCALAPPDATA is not set; pass the socket path explicitly");
        }
        return Path.of(base, "monitord", "monitor.sock");
    }

    /**
     * Picks the socket path: an explicit argument wins, then the MONITOR_SOCKET environment
     * variable (the launch script sets it, which avoids command-line quoting problems with paths
     * that contain spaces), then the default. Blank values count as absent.
     */
    public static Path resolve(String explicit, String envValue) {
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        if (envValue != null && !envValue.isBlank()) return Path.of(envValue);
        return defaultPath();
    }
}
