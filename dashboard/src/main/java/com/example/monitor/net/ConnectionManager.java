package com.example.monitor.net;

//import com.example.monitor.protocol.Commands;
import com.example.monitor.protocol.DecodeException;
import com.example.monitor.protocol.Message;
import com.example.monitor.protocol.MessageDecoder;
import com.example.monitor.protocol.UnsupportedVersionException;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a connection to the daemon alive and delivers decoded messages.
 *
 * Threading: one connection thread connects, reads, decodes, and calls the
 * Listener (so Listener methods must return quickly and must not touch UI
 * nodes; hand work to the UI through a queue). One writer thread performs
 * sends, so send() never blocks its caller.
 */
public final class ConnectionManager implements AutoCloseable {

    /** All methods run on the connection thread. */
    public interface Listener {
        void onState(ConnectionState state, String detail);
        void onMessage(Message message);
        /** A line that could not be decoded; it was skipped and the connection stays up. */
        default void onProblem(String description) {}
    }

    static final int MAX_LINE_BYTES = 256 * 1024;      // the daemon never sends more than 64 KB per line

    private final Path socketPath;
    private final Listener listener;
    private final ReconnectPolicy policy;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(r -> daemonThread(r, "monitor-writer"));

    private volatile SocketChannel current;            // non-null only while connected
    private volatile boolean stopped;
    private Thread thread;

    public ConnectionManager(Path socketPath, Listener listener, ReconnectPolicy policy) {
        this.socketPath = socketPath;
        this.listener = listener;
        this.policy = policy;
    }

    public synchronized void start() {
        if (thread != null) throw new IllegalStateException("already started");
        thread = daemonThread(this::run, "monitor-connection");
        thread.start();
    }

    /** Queues a line for sending. Returns false if there is no connection right now. */
    public boolean send(String line) {
        SocketChannel ch = current;
        if (ch == null || stopped) return false;
        try {
            writer.execute(() -> write(ch, line));
            return true;
        } catch (RuntimeException e) {                  // executor already shut down
            return false;
        }
    }

    @Override
    public void close() {
        stopped = true;
        stopSignal.countDown();
        closeQuietly(current);
        writer.shutdownNow();
        Thread t;
        synchronized (this) { t = thread; }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- connection thread ------------------------------------------------

    private void run() {
        while (!stopped) {
            publish(ConnectionState.CONNECTING, socketPath.toString());

            SocketChannel ch = null;
            String reason;
            boolean versionMismatch = false;
            try {
                ch = connect();
                current = ch;
                publish(ConnectionState.CONNECTED, "connected to " + socketPath);
                reason = readLoop(ch);
            } catch (UnsupportedVersionException e) {
                versionMismatch = true;
                reason = e.getMessage();
            } catch (IOException e) {
                reason = describe(e);
            } catch (RuntimeException e) {              // keep the thread alive whatever happens
                reason = "internal error: " + e;
            } finally {
                current = null;
                closeQuietly(ch);
            }

            if (stopped) break;
            long delay = policy.nextDelayMs();
            publish(versionMismatch ? ConnectionState.VERSION_MISMATCH : ConnectionState.WAITING,
                    reason + "; retrying in " + delay + " ms");
            try {
                if (stopSignal.await(delay, TimeUnit.MILLISECONDS)) break;
            } catch (InterruptedException e) {
                break;
            }
        }
        publish(ConnectionState.STOPPED, "");
    }

    private SocketChannel connect() throws IOException {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            ch.connect(UnixDomainSocketAddress.of(socketPath));
            return ch;
        } catch (IOException | RuntimeException e) {
            closeQuietly(ch);
            throw e;
        }
    }

    private String readLoop(SocketChannel ch) throws IOException, UnsupportedVersionException {
        LineReader reader = new LineReader(Channels.newInputStream(ch), MAX_LINE_BYTES);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            Message message;
            try {
                message = MessageDecoder.decode(line);
            } catch (UnsupportedVersionException e) {
                throw e;
            } catch (DecodeException e) {
                problem(e.getMessage() + " in: " + abbreviate(line));
                continue;
            }
            if (message instanceof Message.Hello) policy.reset();   // the daemon really is serving us
            deliver(message);
        }
        return "the daemon closed the connection";
    }

    // ---- writer thread ----------------------------------------------------

    private void write(SocketChannel ch, String line) {
        try {
            ByteBuffer buf = StandardCharsets.UTF_8.encode(line + "\n");
            while (buf.hasRemaining()) ch.write(buf);
        } catch (IOException e) {
            closeQuietly(ch);                           // the reader notices and reconnects
        }
    }

    // ---- helpers ----------------------------------------------------------

    private void publish(ConnectionState state, String detail) {
        try {
            listener.onState(state, detail);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void deliver(Message message) {
        try {
            listener.onMessage(message);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void problem(String text) {
        try {
            listener.onProblem(text);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        return m != null && !m.isBlank() ? m : e.getClass().getSimpleName();
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    private static void closeQuietly(SocketChannel ch) {
        if (ch == null) return;
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    private static Thread daemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}