package com.example.monitor.session;

import static com.example.monitor.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.net.ConnectionState;
import com.example.monitor.protocol.Message;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class EventQueueTest {
    private final MetricsModel model = new MetricsModel();
    private final Session session = new Session(model, line -> true);
    private final EventQueue queue = new EventQueue();

    @Test
    void deliversEventsInOrder() {
        queue.onState(ConnectionState.CONNECTED, "up");
        queue.onMessage(hello(1000, 600));
        queue.onMessage(live(1));
        queue.onMessage(live(2));
        queue.onProblem("oops");

        assertEquals(5, queue.drainTo(session, 100));
        assertEquals(ConnectionState.CONNECTED, model.connectionState());
        assertEquals("fake", model.daemon());
        assertEquals(2, model.latest().seq());
        assertEquals("oops", model.lastProblem());
        assertFalse(queue.hasPending());
    }

    @Test
    void drainRespectsTheBatchLimit() {
        for (int i = 1; i <= 10; i++) queue.onMessage(live(i));
        assertEquals(3, queue.drainTo(session, 3));
        assertEquals(3, model.latest().seq());
        assertTrue(queue.hasPending());
        assertEquals(7, queue.drainTo(session, 100));
        assertEquals(10, model.latest().seq());
        assertEquals(0, queue.drainTo(session, 100));
    }

    @Test
    void overloadDiscardsSnapshotsButNeverStateChanges() {
        for (int i = 1; i <= EventQueue.MAX_PENDING; i++) queue.onMessage(live(i));
        for (int i = 0; i < 5; i++) queue.onMessage(live(20_000 + i));        // over the cap: discarded
        assertEquals(5, queue.discarded());

        queue.onState(ConnectionState.WAITING, "lost");                       // state changes always get through
        assertEquals(EventQueue.MAX_PENDING + 1, queue.drainTo(session, Integer.MAX_VALUE));
        assertEquals(ConnectionState.WAITING, model.connectionState());
        assertEquals(EventQueue.MAX_PENDING, model.latest().seq());
    }

    @Test
    void worksWithAProducerOnAnotherThread() throws Exception {
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 2000; i++) queue.onMessage(live(i));
        });
        producer.start();

        int processed = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (processed < 2000 && System.nanoTime() < deadline) processed += queue.drainTo(session, 50);
        producer.join();

        assertEquals(2000, processed);
        model.commit();
        assertEquals(2000, model.latest().seq());
        List<Long> seqs = model.history().stream().map(Message.Snapshot::seq).toList();
        assertEquals(MetricsModel.DEFAULT_HISTORY, seqs.size());              // the newest 600
        for (int i = 1; i < seqs.size(); i++) assertEquals(seqs.get(i - 1) + 1, seqs.get(i));
    }
}