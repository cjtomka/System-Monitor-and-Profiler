package com.example.monitor.session;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.net.ConnectionState;
import com.example.monitor.protocol.Commands;
import com.example.monitor.protocol.Message;

/**
 * Per-connection rules between the daemon and the model. FX thread only.
 *
 * The rules that must hold however the UI is wired:
 *  - hello can arrive several times per connection (the daemon re-sends it whenever the
 *    interval changes), so only the FIRST one of a connection requests history;
 *  - nothing is ever sent in reaction to a hello except that one history request;
 *  - setInterval is for user actions, and does nothing when the interval already matches.
 */
public final class Session {

    /** How lines reach the daemon; ConnectionManager::send in the application. */
    public interface Sender {
        boolean send(String line);
    }

    private final MetricsModel model;
    private final Sender sender;
    private boolean historyRequested;

    public Session(MetricsModel model, Sender sender) {
        this.model = model;
        this.sender = sender;
    }

    public void onState(ConnectionState state, String detail) {
        if (state == ConnectionState.CONNECTED) {
            historyRequested = false;
            model.beginConnection();
        }
        model.applyState(state, detail);
    }

    public void onMessage(Message m) {
        if (m instanceof Message.Hello h) {
            model.applyHello(h);
            if (!historyRequested) {
                historyRequested = sender.send(Commands.getHistory(0));   // if it could not be sent, retry on the next hello
            }
        } else if (m instanceof Message.Snapshot s) {
            model.applySnapshot(s);
        } else if (m instanceof Message.History h) {
            model.applyHistory(h);
        } else if (m instanceof Message.ErrorMessage e) {
            model.applyProblem(e.code() + ": " + e.message());
        }
        // Pong and Unknown are deliberately ignored.
    }

    public void onProblem(String text) {
        model.applyProblem(text);
    }

    /** User action only. The model's interval changes when the daemon confirms it with a hello. */
    public void setInterval(int ms) {
        if (ms == model.intervalMs()) return;
        sender.send(Commands.setInterval(ms));
    }
}
