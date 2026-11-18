package com.example.monitor.protocol;

import java.util.List;

/**
 * Everything the daemon can send. Field names map to the wire's snake_case
 * (tsMs <-> ts_ms) through the decoder's naming strategy. A section the daemon
 * could not read arrives as JSON null and is null here, never a zeroed object.
 */
public sealed interface Message {

    record Hello(String daemon, int cores, int intervalMs, int historyCapacity) implements Message {}

    /** Also used for history entries, which lack topProcs and dropped (null / 0). */
    record Snapshot(long seq, long tsMs, boolean warmingUp,
                    CpuStats cpu, MemStats mem,
                    List<DiskStats> disks, List<ProcStats> topProcs,
                    long dropped) implements Message {}

    record History(int count, boolean last, List<Snapshot> snapshots) implements Message {}

    record ErrorMessage(String code, String message) implements Message {}

    record Pong(Long id) implements Message {}

    /** A message type this client does not know: ignored by design, never an error. */
    record Unknown(String type) implements Message {}

    record CpuStats(double totalPct, List<Double> coresPct) {}
    record MemStats(long totalBytes, long availBytes, long commitBytes, long commitLimitBytes) {}
    record DiskStats(String name, long totalBytes, long freeBytes, long readBps, long writeBps, double queue) {}
    record ProcStats(long pid, String name, double cpuPct, long rssBytes) {}
}