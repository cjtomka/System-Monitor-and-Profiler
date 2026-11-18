package com.example.monitor.session;

import static com.example.monitor.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.net.ConnectionState;
import com.example.monitor.protocol.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.Test;

class SessionTest {
    private final MetricsModel model = new MetricsModel();
    private final List<String> sent = new ArrayList<>();
    private boolean connected = true;
    private final Session session = new Session(model, line -> {
        if (!connected) return false;
        sent.add(line);
        return true;
    });

    private long count(String type) {
        return sent.stream().filter(l -> l.contains("\"type\":\"" + type + "\"")).count();
    }

    private static List<Long> seqs(MetricsModel m) {
        return m.history().stream().map(Message.Snapshot::seq).toList();
    }

    @Test
    void firstHelloRequestsHistoryExactlyOnce() {
        session.onState(ConnectionState.CONNECTED, "");
        for (int i = 0; i < 5; i++) session.onMessage(hello(1000, 600));
        assertEquals(1, count("get_history"));
        assertEquals(0, count("set_interval"));
    }

    @Test
    void reconnectingStartsFromAFreshBufferAndRequestsHistoryAgain() {
        session.onState(ConnectionState.CONNECTED, "");
        session.onMessage(hello(1000, 600));
        session.onMessage(live(7));
        model.commit();
        assertEquals(List.of(7L), seqs(model));

        session.onState(ConnectionState.WAITING, "lost");
        model.commit();
        assertEquals(List.of(7L), seqs(model));                  // stale data stays visible while reconnecting
        assertEquals(ConnectionState.WAITING, model.connectionState());

        session.onState(ConnectionState.CONNECTED, "");          // the new connection: sequence numbers may have restarted
        model.commit();
        assertTrue(model.history().isEmpty());
        assertNull(model.latest());
        session.onMessage(hello(1000, 600));
        assertEquals(2, count("get_history"));
    }

    @Test
    void aHistoryRequestThatCouldNotBeSentIsRetriedOnTheNextHello() {
        connected = false;
        session.onState(ConnectionState.CONNECTED, "");
        session.onMessage(hello(1000, 600));
        assertEquals(0, sent.size());
        connected = true;
        session.onMessage(hello(1000, 600));
        assertEquals(1, count("get_history"));
    }

    @Test
    void liveSnapshotsAndHistoryChunksMergeWithoutDuplicates() {
        session.onState(ConnectionState.CONNECTED, "");
        session.onMessage(hello(1000, 600));
        session.onMessage(live(10));
        session.onMessage(history(false, LongStream.rangeClosed(1, 4).toArray()));
        session.onMessage(live(11));
        session.onMessage(history(true, LongStream.rangeClosed(5, 9).toArray()));
        model.commit();

        assertEquals(LongStream.rangeClosed(1, 11).boxed().toList(), seqs(model));
        assertEquals(11, model.latest().seq());
        assertNull(model.history().get(0).topProcs());           // history entry
        assertNotNull(model.history().get(9).topProcs());        // seq 10 was live and kept its process list
    }

    @Test
    void setIntervalDoesNothingWhenTheIntervalIsAlreadyCurrent() {
        session.onState(ConnectionState.CONNECTED, "");
        session.onMessage(hello(1000, 600));
        session.setInterval(1000);
        assertEquals(0, count("set_interval"));
        session.setInterval(200);
        assertEquals(1, count("set_interval"));
        assertTrue(sent.get(sent.size() - 1).contains("\"interval_ms\":200"));
    }

    @Test
    void anEchoingDaemonCannotCauseACommandLoop() {
        MetricsModel m = new MetricsModel();
        List<String> out = new ArrayList<>();
        Session[] holder = new Session[1];
        holder[0] = new Session(m, line -> {
            out.add(line);
            if (line.contains("\"type\":\"set_interval\"")) {
                holder[0].onMessage(hello(200, 600));            // the daemon confirms with a hello, as the real one does
            }
            return true;
        });

        holder[0].onState(ConnectionState.CONNECTED, "");
        holder[0].onMessage(hello(1000, 600));
        holder[0].setInterval(200);
        for (int i = 0; i < 50; i++) holder[0].onMessage(hello(200, 600));   // and a storm of further hellos

        assertEquals(1, out.stream().filter(l -> l.contains("set_interval")).count());
        assertEquals(1, out.stream().filter(l -> l.contains("get_history")).count());
        assertEquals(200, m.intervalMs());
    }

    @Test
    void latestFollowsTheNewestLiveSnapshot() {
        session.onState(ConnectionState.CONNECTED, "");
        session.onMessage(hello(1000, 600));
        session.onMessage(liveDropped(10, 3));
        assertEquals(10, model.latest().seq());
        assertEquals(3, model.dropped());

        session.onMessage(liveDropped(9, 99));                   // stale: must not replace the newer one
        assertEquals(10, model.latest().seq());
        assertEquals(3, model.dropped());
        model.commit();
        assertEquals(List.of(9L, 10L), seqs(model));
    }

    @Test
    void errorsAndProblemsAreRecorded() {
        session.onMessage(new Message.ErrorMessage("bad_json", "The line is not valid JSON."));
        assertEquals("bad_json: The line is not valid JSON.", model.lastProblem());
        session.onProblem("invalid JSON in: xyz");
        assertEquals("invalid JSON in: xyz", model.lastProblem());

        session.onMessage(new Message.Pong(7L));                 // ignored
        session.onMessage(new Message.Unknown("future"));        // ignored
        assertEquals("invalid JSON in: xyz", model.lastProblem());
    }

    @Test
    void historyIsPublishedOncePerCommit() {
        session.onState(ConnectionState.CONNECTED, "");
        session.onMessage(hello(1000, 600));
        model.commit();                                          // flush the clear from the new connection

        AtomicInteger changes = new AtomicInteger();
        model.history().addListener((ListChangeListener<Message.Snapshot>) c -> changes.incrementAndGet());

        for (int i = 1; i <= 50; i++) session.onMessage(live(i));
        assertEquals(0, changes.get());                          // nothing published mid-batch
        model.commit();
        assertEquals(1, changes.get());                          // 50 messages, one change notification
        model.commit();
        assertEquals(1, changes.get());                          // nothing new, no notification
        assertEquals(50, model.history().size());
    }
}
