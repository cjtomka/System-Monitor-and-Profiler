package com.example.monitor.ui;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.net.ConnectionManager;
import com.example.monitor.net.ReconnectPolicy;
import com.example.monitor.net.SocketPaths;
import com.example.monitor.protocol.Message;
import com.example.monitor.session.EventQueue;
import com.example.monitor.session.Session;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Run with: mvn javafx:run   (optionally -Djavafx.args="C:\path\to\monitor.sock") */
public final class StatusApp extends Application {
    private static final double GIB = 1024.0 * 1024.0 * 1024.0;
    private static final double MIB = 1024.0 * 1024.0;

    private ConnectionManager connection;
    private ModelDriver driver;

    @Override
    public void start(Stage stage) {
        List<String> raw = getParameters().getRaw();
        Path socket = SocketPaths.resolve(raw.isEmpty() ? null : raw.get(0), System.getenv("MONITOR_SOCKET"));

        MetricsModel model = new MetricsModel();
        EventQueue queue = new EventQueue();
        connection = new ConnectionManager(socket, queue, new ReconnectPolicy(500, 5000));
        Session session = new Session(model, connection::send);

        TextArea body = new TextArea();
        body.setEditable(false);
        body.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 13px;");
        Runnable refresh = () -> body.setText(render(model));

        model.latestProperty().addListener((o, a, b) -> refresh.run());
        model.connectionStateProperty().addListener((o, a, b) -> refresh.run());
        model.lastProblemProperty().addListener((o, a, b) -> refresh.run());
        model.history().addListener((ListChangeListener<Message.Snapshot>) c -> refresh.run());

        // Interval selector. The daemon's hello is the source of truth: the model's interval is
        // pushed INTO the combo box (guarded), and only a user's choice goes OUT as a command.
        ComboBox<Integer> interval = new ComboBox<>(FXCollections.observableArrayList(100, 200, 500, 1000, 2000, 5000));
        boolean[] syncing = {false};
        model.intervalMsProperty().addListener((o, a, v) -> {
            syncing[0] = true;
            interval.setValue(v.intValue());
            syncing[0] = false;
            refresh.run();
        });
        interval.valueProperty().addListener((o, a, v) -> {
            if (!syncing[0] && v != null) session.setInterval(v);
        });

        Label title = new Label("Status probe");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, title, spacer, new Label("Interval (ms):"), interval);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));

        BorderPane root = new BorderPane(body);
        root.setTop(top);

        driver = new ModelDriver(queue, session, model);
        driver.start();
        connection.start();
        refresh.run();

        stage.setTitle("Monitor status probe: " + socket);
        stage.setScene(new Scene(root, 820, 560));
        stage.show();
    }

    @Override
    public void stop() {
        if (driver != null) driver.stop();
        if (connection != null) connection.close();
    }

    private static String render(MetricsModel m) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(f("connection   %s  %s%n", m.connectionState(), m.stateDetail()));
        sb.append(f("daemon       %s, %d cores, interval %d ms, history capacity %d%n",
                m.daemon().isEmpty() ? "-" : m.daemon(), m.cores(), m.intervalMs(), m.historyCapacity()));
        List<Message.Snapshot> h = m.history();
        sb.append(f("history      %d snapshots%s | dropped by backpressure: %d%n", h.size(),
                h.isEmpty() ? "" : f(", seq %d..%d", h.get(0).seq(), h.get(h.size() - 1).seq()), m.dropped()));
        if (!m.lastProblem().isEmpty()) sb.append(f("last problem  %s%n", m.lastProblem()));
        sb.append(System.lineSeparator());

        Message.Snapshot s = m.latest();
        if (s == null) return sb.append("no snapshot yet").toString();

        sb.append(f("snapshot     seq %d%s%n", s.seq(), s.warmingUp() ? " (warming up)" : ""));
        if (s.cpu() == null) {
            sb.append("cpu          n/a\n");
        } else {
            String cores = s.cpu().coresPct() == null ? "" : s.cpu().coresPct().stream()
                    .map(v -> f("%.0f", v)).collect(Collectors.joining(" "));
            sb.append(f("cpu          %.1f%%   cores: %s%n", s.cpu().totalPct(), cores));
        }
        if (s.mem() == null) {
            sb.append("memory       n/a\n");
        } else {
            Message.MemStats mem = s.mem();
            sb.append(f("memory       %.1f of %.1f GiB used   commit %.1f of %.1f GiB%n",
                    (mem.totalBytes() - mem.availBytes()) / GIB, mem.totalBytes() / GIB,
                    mem.commitBytes() / GIB, mem.commitLimitBytes() / GIB));
        }
        if (s.disks() == null) {
            sb.append("disks        n/a\n");
        } else {
            for (Message.DiskStats d : s.disks()) {
                sb.append(f("disk %-3s      %.0f of %.0f GiB free   read %.1f MB/s   write %.1f MB/s   queue %.2f%n",
                        d.name(), d.freeBytes() / GIB, d.totalBytes() / GIB,
                        d.readBps() / MIB, d.writeBps() / MIB, d.queue()));
            }
        }
        if (s.topProcs() == null) {
            sb.append("processes    n/a\n");
        } else {
            sb.append("processes    (top 5)\n");
            s.topProcs().stream().limit(5).forEach(p ->
                    sb.append(f("  %-28s pid %-6d %5.1f%%  %6.0f MB%n", p.name(), p.pid(), p.cpuPct(), p.rssBytes() / MIB)));
        }
        return sb.toString();
    }

    private static String f(String fmt, Object... args) {
        return String.format(Locale.ROOT, fmt, args);
    }

    public static void main(String[] args) {
        launch(args);
    }
}