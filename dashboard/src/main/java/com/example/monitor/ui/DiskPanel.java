package com.example.monitor.ui;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.protocol.Message;
import com.example.monitor.viewmodel.AxisScale;
import com.example.monitor.viewmodel.ChartSeries;
import com.example.monitor.viewmodel.Metrics;
import com.example.monitor.viewmodel.StatusText;
import com.example.monitor.viewmodel.Units;
import com.example.monitor.viewmodel.Usage;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Disks: capacity bars for every drive, and read/write throughput over time for one drive. */
final class DiskPanel extends VBox {
    private static final int MAX_POINTS = 1500;
    private static final double MIN_TOP = 1024.0 * 1024.0;      // 1 MiB/s: idle noise must not fill the graph

    private final MetricsModel model;
    private final UsageBars bars = new UsageBars();
    private final LineGraph graph = new LineGraph();
    private final Label stats = new Label();
    private final ComboBox<String> driveBox = new ComboBox<>();
    private final WindowSelector windowBox = new WindowSelector();
    private boolean updating;       // true while the drive list is being refreshed from the data

    DiskPanel(MetricsModel model) {
        this.model = model;
        setSpacing(8);
        setPadding(new Insets(10));

        Label title = new Label("Disks");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        stats.setStyle("-fx-text-fill: #5f6b7a;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, stats, spacer, new Label("Drive:"), driveBox, windowBox);
        header.setAlignment(Pos.CENTER_LEFT);

        graph.setStyles(List.of(new LineGraph.LineStyle(Theme.LINE, false), new LineGraph.LineStyle(Theme.WRITE, false)));
        VBox.setVgrow(graph, Priority.ALWAYS);
        getChildren().addAll(header, bars, graph);

        model.revisionProperty().addListener((o, a, b) -> refresh());
        windowBox.valueProperty().addListener((o, a, b) -> refresh());
        driveBox.valueProperty().addListener((o, a, b) -> { if (!updating) refresh(); });
        refresh();
    }

    private void refresh() {
        Message.Snapshot newest = Metrics.newest(model.history());
        bars.setRows(Usage.disks(newest));

        List<String> names = Metrics.driveNames(newest);
        updating = true;                                        // changing the list also changes the value
        if (!names.equals(driveBox.getItems())) {
            String keep = driveBox.getValue();
            driveBox.getItems().setAll(names);
            if (keep != null && names.contains(keep)) driveBox.setValue(keep);
            else driveBox.setValue(names.isEmpty() ? null : names.get(0));
        }
        updating = false;

        String drive = driveBox.getValue();
        List<ChartSeries.Extractor> extractors = drive == null
                ? List.of() : List.of(Metrics.diskRead(drive), Metrics.diskWrite(drive));
        ChartSeries.Chart chart = ChartSeries.build(model.history(), windowBox.seconds(), MAX_POINTS, extractors);
        AxisScale.YAxis axis = AxisScale.auto(chart.maxValue(), MIN_TOP);

        graph.setData(chart, axis, v -> Units.scaled(v, axis.unitIndex(), true));
        graph.setOverlay(StatusText.overlay(model.connectionState(), !chart.isEmpty()));

        Message.DiskStats d = Metrics.findDisk(newest, drive);
        stats.setText(d == null ? "no data yet"
                : String.format(Locale.ROOT, "read %s    write %s    queue %.2f",
                        Units.rate(d.readBps()), Units.rate(d.writeBps()), d.queue()));
    }
}
