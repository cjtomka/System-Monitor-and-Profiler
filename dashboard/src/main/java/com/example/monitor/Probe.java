package com.example.monitor;

import com.example.monitor.net.ConnectionManager;
import com.example.monitor.net.ConnectionState;
import com.example.monitor.net.ReconnectPolicy;
import com.example.monitor.net.SocketPaths;
import com.example.monitor.protocol.Commands;
import com.example.monitor.protocol.Message;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Console probe: connects, prints every decoded message, and checks history contiguity. */
public final class Probe implements ConnectionManager.Listener {
    private static final double GIB = 1024.0 * 1024.0 * 1024.0;

    private boolean greeted; // true once the initial commands have been sent on this connection
    private final boolean wantHistory, wantPing;
    private final int wantInterval;
    private volatile ConnectionManager cm;

    // history bookkeeping (connection thread only)
    private long histTotal, histFirst, histPrev;
    private boolean histGap;
    private long histGapAt;

    private Probe(boolean wantHistory, boolean wantPing, int wantInterval) {
        this.wantHistory = wantHistory;
        this.wantPing = wantPing;
        this.wantInterval = wantInterval;
    }

    public static void main(String[] args) throws Exception {
        Path socket = null;
        boolean history = false, ping = false;
        int seconds = 0, interval = 0;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--socket"   -> socket = Path.of(args[++i]);
                    case "--history"  -> history = true;
                    case "--ping"     -> ping = true;
                    case "--interval" -> interval = Integer.parseInt(args[++i]);
                    case "--seconds"  -> seconds = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException(args[i]);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("usage: Probe [--socket PATH] [--history] [--ping] [--interval MS] [--seconds N]");
            return;
        }
        if (socket == null) socket = SocketPaths.defaultPath();

        Probe probe = new Probe(history, ping, interval);
        ConnectionManager manager = new ConnectionManager(socket, probe, new ReconnectPolicy(500, 5000));
        probe.cm = manager;
        manager.start();

        if (seconds > 0) {
            Thread.sleep(seconds * 1000L);
            manager.close();
        } else {
            Runtime.getRuntime().addShutdownHook(new Thread(manager::close));
            Thread.currentThread().join();                      // until Ctrl+C
        }
    }

    @Override
    public void onState(ConnectionState state, String detail) {
        System.out.println("[state] " + state + " " + detail);
        if (state == ConnectionState.CONNECTED) greeted = false;      // a new connection starts fresh
    }

    @Override
    public void onProblem(String description) {
        System.out.println("[problem] " + description);
    }

    @Override
    public void onMessage(Message m) {
        if (m instanceof Message.Hello h) {
            System.out.println(f("hello    daemon=%s cores=%d interval=%d ms history_capacity=%d",
                    h.daemon(), h.cores(), h.intervalMs(), h.historyCapacity()));

            // The daemon can send hello again later (for example, after anyone changes the
            // interval), so the opening commands go out only on the FIRST hello of a connection.
            if (!greeted) {
                greeted = true;
                histTotal = 0;
                histGap = false;
                if (wantHistory) cm.send(Commands.getHistory(0));
                if (wantPing)    cm.send(Commands.ping(7));
                if (wantInterval > 0 && h.intervalMs() != wantInterval)     // nothing to do if it already matches
                    cm.send(Commands.setInterval(wantInterval));
            }
        } else if (m instanceof Message.Snapshot s) {
            System.out.println(describe(s));
        } else if (m instanceof Message.History h) {
            printHistory(h);
        } else if (m instanceof Message.Pong p) {
            System.out.println("pong     id=" + p.id());
        } else if (m instanceof Message.ErrorMessage e) {
            System.out.println(f("error    %s: %s", e.code(), e.message()));
        } else if (m instanceof Message.Unknown u) {
            System.out.println("unknown message type '" + u.type() + "' (ignored)");
        }
    }

    private void printHistory(Message.History h) {
        List<Message.Snapshot> list = h.snapshots() == null ? List.of() : h.snapshots();
        for (Message.Snapshot s : list) {
            if (histTotal == 0) histFirst = s.seq();
            else if (s.seq() != histPrev + 1 && !histGap) { histGap = true; histGapAt = s.seq(); }
            histPrev = s.seq();
            histTotal++;
        }
        String range = list.isEmpty() ? "-" : list.get(0).seq() + ".." + list.get(list.size() - 1).seq();
        System.out.println(f("history  chunk: %d snapshots, seq %s%s", list.size(), range, h.last() ? ", last" : ""));
        if (h.last()) {
            System.out.println(histTotal == 0
                    ? "history complete: the daemon had no snapshots yet"
                    : f("history complete: %d snapshots, seq %d..%d, %s", histTotal, histFirst, histPrev,
                        histGap ? "NOT contiguous (gap at seq " + histGapAt + ")" : "contiguous"));
        }
    }

    private static String describe(Message.Snapshot s) {
        String cpu = s.cpu() == null ? "null"
                : f("%.1f%% (%d cores)", s.cpu().totalPct(), s.cpu().coresPct() == null ? 0 : s.cpu().coresPct().size());
        String mem = s.mem() == null ? "null"
                : f("%.1f/%.1f GiB free", s.mem().availBytes() / GIB, s.mem().totalBytes() / GIB);
        String disks = s.disks() == null ? "null" : s.disks().size() + " drives";
        String procs;
        if (s.topProcs() == null) procs = "null";
        else if (s.topProcs().isEmpty()) procs = "none";
        else {
            Message.ProcStats top = s.topProcs().get(0);
            procs = f("%d (top: %s %.1f%%)", s.topProcs().size(), top.name(), top.cpuPct());
        }
        return f("snapshot seq=%d warm=%b cpu=%s mem=%s disks=%s procs=%s dropped=%d",
                s.seq(), s.warmingUp(), cpu, mem, disks, procs, s.dropped());
    }

    private static String f(String fmt, Object... args) {
        return String.format(Locale.ROOT, fmt, args);
    }
}
