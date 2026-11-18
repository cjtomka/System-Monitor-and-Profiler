package com.example.monitor.model;

import static com.example.monitor.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.monitor.net.ConnectionState;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MetricsModelTest {
    private final MetricsModel model = new MetricsModel();

    @Test
    void theRevisionAdvancesOncePerCommitThatHasChanges() {
        AtomicInteger notifications = new AtomicInteger();
        model.revisionProperty().addListener((o, a, b) -> notifications.incrementAndGet());

        for (int i = 1; i <= 10; i++) model.applySnapshot(live(i));
        assertEquals(0, notifications.get());                          // nothing published mid-batch
        assertEquals(0, model.revision());

        model.commit();
        assertEquals(1, notifications.get());                          // ten snapshots, one refresh
        assertEquals(1, model.revision());

        model.commit();                                                // nothing new
        assertEquals(1, notifications.get());
    }

    @Test
    void stateHelloAndProblemChangesAlsoAdvanceTheRevision() {
        model.applyState(ConnectionState.CONNECTED, "");
        model.commit();
        assertEquals(1, model.revision());

        model.applyHello(hello(1000, 600));
        model.commit();
        assertEquals(2, model.revision());

        model.applyProblem("oops");
        model.commit();
        assertEquals(3, model.revision());
    }

    @Test
    void aNewConnectionClearsLatestDroppedAndHistory() {
        model.applySnapshot(liveDropped(5, 4));
        model.commit();
        assertEquals(4, model.dropped());
        assertEquals(1, model.history().size());
        long before = model.revision();

        model.beginConnection();
        model.commit();
        assertNull(model.latest());
        assertEquals(0, model.dropped());
        assertTrue(model.history().isEmpty());
        assertEquals(before + 1, model.revision());
    }
}
