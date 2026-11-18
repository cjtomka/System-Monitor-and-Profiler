package com.example.monitor.net;

/** Exponential backoff. Not thread-safe: only the connection thread uses it. */
public final class ReconnectPolicy {
    private final long initialMs;
    private final long maxMs;
    private long nextMs;

    public ReconnectPolicy(long initialMs, long maxMs) {
        if (initialMs <= 0 || maxMs < initialMs) throw new IllegalArgumentException("need 0 < initial <= max");
        this.initialMs = initialMs;
        this.maxMs = maxMs;
        this.nextMs = initialMs;
    }

    /** The delay to wait now; each call doubles the next one up to the cap. */
    public long nextDelayMs() {
        long delay = nextMs;
        nextMs = Math.min(maxMs, nextMs * 2);
        return delay;
    }

    public void reset() {
        nextMs = initialMs;
    }
}
