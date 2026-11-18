package com.example.monitor.net;

import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.protocol.Commands;
import com.example.monitor.protocol.Message;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class ConnectionManagerTest {

    private static final String HELLO =
            "{\"v\":1,\"type\":\"hello\",\"daemon\":\"fake\",\"cores\":8,\"interval_ms\":1000,\"history_capacity\":600}";
    private static final String SNAPSHOT =
            "{\"v\":1,\"type\":\"snapshot\",\"seq\":7,\"ts_ms\":1,\"warming_up\":false,"
            + "\"cpu\":null,\"mem\":null,\"disks\":null,\"top_procs\":null,\"dropped\":0}";

    /** Records every state change, message, and problem, in order. */
    private static final class Recorder implements ConnectionManager.Listener {
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();

        @Override public void onState(ConnectionState s, String detail) { events.add(s); }
        @Override public void onMessage(Message m) { events.add(m); }
        @Override public void onProblem(String text) { events.add(text); }

        /** Skips events until one of the given type matches; fails after 5 seconds. */
        <T> T await(Class<T> type, Predicate<T> match) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (;;) {
                Object e = events.poll(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
                if (e == null) throw new AssertionError("timed out waiting for " + type.getSimpleName());
                if (type.isInstance(e) && match.test(type.cast(e))) return type.cast(e);
            }
        }

        void awaitState(ConnectionState state) throws InterruptedException {
            await(ConnectionState.class, s -> s == state);
        }
    }

    /** A stand-in for the daemon: a Unix-domain server in a temp directory. */
    private static final class FakeServer implements AutoCloseable {
        final Path dir;
        final Path sock;
        ServerSocketChannel server;

        FakeServer() throws IOException {
            dir = Files.createTempDirectory("mon");
            sock = dir.resolve("s.sock");
        }

        void listen() throws IOException {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(sock));
        }

        SocketChannel accept() throws IOException {
            return server.accept();
        }

        static void writeLine(SocketChannel c, String line) throws IOException {
            ByteBuffer b = StandardCharsets.UTF_8.encode(line + "\n");
            while (b.hasRemaining()) c.write(b);
        }

        @Override public void close() {
            try {
                if (server != null) server.close();
                Files.deleteIfExists(sock);
                Files.deleteIfExists(dir);
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    void connectsWhenTheDaemonAppearsAndReconnectsAfterItDisappears() throws Exception {
        Recorder rec = new Recorder();
        try (FakeServer fs = new FakeServer();
             ConnectionManager cm = new ConnectionManager(fs.sock, rec, new ReconnectPolicy(50, 200))) {
            cm.start();
            rec.awaitState(ConnectionState.WAITING);               // dashboard started before the daemon

            fs.listen();
            try (SocketChannel c1 = fs.accept()) {
                rec.awaitState(ConnectionState.CONNECTED);
                FakeServer.writeLine(c1, HELLO);
                FakeServer.writeLine(c1, SNAPSHOT);
                assertEquals(8, rec.await(Message.Hello.class, h -> true).cores());
                assertEquals(7, rec.await(Message.Snapshot.class, s -> true).seq());
            }                                                       // the daemon goes away

            rec.awaitState(ConnectionState.WAITING);
            try (SocketChannel c2 = fs.accept()) {                  // and comes back
                rec.awaitState(ConnectionState.CONNECTED);
                FakeServer.writeLine(c2, HELLO);
                rec.await(Message.Hello.class, h -> true);
            }
        }
    }

    @Test
    void sendsCommandsToTheDaemon() throws Exception {
        Recorder rec = new Recorder();
        try (FakeServer fs = new FakeServer();
             ConnectionManager cm = new ConnectionManager(fs.sock, rec, new ReconnectPolicy(50, 200))) {
            fs.listen();
            cm.start();
            try (SocketChannel c = fs.accept()) {
                rec.awaitState(ConnectionState.CONNECTED);
                assertTrue(cm.send(Commands.ping(7)));
                String line = new LineReader(Channels.newInputStream(c), 4096).readLine();
                assertEquals("{\"v\":1,\"type\":\"ping\",\"id\":7}", line);
            }
        }
    }

    @Test
    void reportsAVersionMismatch() throws Exception {
        Recorder rec = new Recorder();
        try (FakeServer fs = new FakeServer();
             ConnectionManager cm = new ConnectionManager(fs.sock, rec, new ReconnectPolicy(50, 200))) {
            fs.listen();
            cm.start();
            try (SocketChannel c = fs.accept()) {
                rec.awaitState(ConnectionState.CONNECTED);
                FakeServer.writeLine(c, "{\"v\":2,\"type\":\"hello\"}");
                rec.awaitState(ConnectionState.VERSION_MISMATCH);
            }
        }
    }

    @Test
    void aBadLineIsReportedAndSkippedWithoutDroppingTheConnection() throws Exception {
        Recorder rec = new Recorder();
        try (FakeServer fs = new FakeServer();
             ConnectionManager cm = new ConnectionManager(fs.sock, rec, new ReconnectPolicy(50, 200))) {
            fs.listen();
            cm.start();
            try (SocketChannel c = fs.accept()) {
                rec.awaitState(ConnectionState.CONNECTED);
                FakeServer.writeLine(c, "this is not json");
                FakeServer.writeLine(c, SNAPSHOT);
                rec.await(String.class, s -> s.contains("invalid JSON"));
                assertEquals(7, rec.await(Message.Snapshot.class, s -> true).seq());   // same connection, still alive
            }
        }
    }
}
