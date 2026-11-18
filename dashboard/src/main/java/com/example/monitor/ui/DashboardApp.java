package com.example.monitor.ui;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.net.ConnectionManager;
import com.example.monitor.net.ConnectionState;
import com.example.monitor.net.ReconnectPolicy;
import com.example.monitor.net.SocketPaths;
import com.example.monitor.session.EventQueue;
import com.example.monitor.session.Session;
import com.example.monitor.viewmodel.StatusText;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/** Run with: mvn javafx:run   (optionally -Djavafx.args="C:\path\to\monitor.sock") */
public final class DashboardApp extends Application {
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

        // top bar
        Circle dot = new Circle(6, Theme.IDLE);
        Label status = new Label("Connecting");
        Label daemonInfo = new Label();
        daemonInfo.setStyle("-fx-text-fill: #5f6b7a;");
        ComboBox<Integer> interval = new ComboBox<>(FXCollections.observableArrayList(100, 200, 500, 1000, 2000, 5000));
        interval.setDisable(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, dot, status, daemonInfo, spacer, new Label("Sample interval (ms):"), interval);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10, 12, 6, 12));

        TabPane tabs = new TabPane(
                tab("CPU", new CpuPanel(model)),
                tab("Memory", new MemoryPanel(model)),
                tab("Disks", new DiskPanel(model)),
                tab("Processes", new ProcessPanel(model)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Label footer = new Label();
        footer.setStyle("-fx-text-fill: #5f6b7a; -fx-font-size: 11px;");
        footer.setPadding(new Insets(4, 12, 8, 12));

        // The daemon's hello is the source of truth: the model's interval is pushed INTO the combo box
        // (guarded), and only a user's choice goes OUT as a command.
        boolean[] syncing = {false};
        interval.valueProperty().addListener((o, a, v) -> {
            if (!syncing[0] && v != null) session.setInterval(v);
        });

        model.revisionProperty().addListener((o, a, b) -> {
            ConnectionState state = model.connectionState();
            dot.setFill(stateColor(state));
            status.setText(StatusText.statusLine(state, model.stateDetail()));
            daemonInfo.setText(model.daemon().isEmpty() ? "" : String.format(Locale.ROOT, "%s · %d cores",
                    model.daemon(), model.cores()));
            interval.setDisable(state != ConnectionState.CONNECTED);
            if (model.intervalMs() > 0) {
                syncing[0] = true;
                interval.setValue(model.intervalMs());
                syncing[0] = false;
            }
            footer.setText(String.format(Locale.ROOT, "history %d of %d snapshots · dropped by backpressure: %d%s",
                    model.history().size(), model.historyCapacity(), model.dropped(),
                    model.lastProblem().isEmpty() ? "" : " · last problem: " + model.lastProblem()));
        });

        BorderPane root = new BorderPane(tabs);
        root.setTop(top);
        root.setBottom(footer);
        root.setBackground(new Background(new BackgroundFill(Theme.PAGE_BG, null, null)));

        driver = new ModelDriver(queue, session, model);
        driver.start();
        connection.start();

        stage.setTitle("System Monitor: " + socket);
        stage.setScene(new Scene(root, 1000, 680));
        stage.show();
    }

    @Override
    public void stop() {
        if (driver != null) driver.stop();
        if (connection != null) connection.close();
    }

    private static Tab tab(String title, Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private static Color stateColor(ConnectionState s) {
        return switch (s) {
            case CONNECTED           -> Theme.OK;
            case CONNECTING, WAITING -> Theme.WARN;
            case VERSION_MISMATCH    -> Theme.BAD;
            case STOPPED             -> Theme.IDLE;
        };
    }

    public static void main(String[] args) {
        launch(args);
    }
}