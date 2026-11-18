package com.example.monitor.protocol;

/** Builders for the lines a client may send. All values are numeric, so no escaping is needed. */
public final class Commands {
    private Commands() {}

    public static String ping(long id) {
        return "{\"v\":1,\"type\":\"ping\",\"id\":" + id + "}";
    }

    public static String setInterval(int intervalMs) {
        return "{\"v\":1,\"type\":\"set_interval\",\"interval_ms\":" + intervalMs + "}";
    }

    /** count <= 0 asks for everything the daemon has. */
    public static String getHistory(int count) {
        return count > 0
                ? "{\"v\":1,\"type\":\"get_history\",\"count\":" + count + "}"
                : "{\"v\":1,\"type\":\"get_history\"}";
    }
}