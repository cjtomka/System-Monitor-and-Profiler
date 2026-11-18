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
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Memory: usage bars for physical memory and commit charge, and physical use over time. */
final class MemoryPanel extends VBox {
    private static final int MAX_POINTS = 1500;
    private static final List<ChartSeries.Extractor> EXTRACTORS = List.of(Metrics.memInUse());

    private final MetricsModel model;
    private final UsageBars bars = new UsageBars();
    private final LineGraph graph = new LineGraph();
    private final Label stats = new Label();
    private final WindowSelector windowBox = new WindowSelector();

    MemoryPanel(MetricsModel model) {
        this.model = model;
        setSpacing(8);
        setPadding(new Insets(10));

        Label title = new Label("Memory");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        stats.setStyle("-fx-text-fill: #5f6b7a;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, stats, spacer, windowBox);
        header.setAlignment(Pos.CENTER_LEFT);

        graph.setStyles(List.of(new LineGraph.LineStyle(Theme.MEMORY, true)));
        VBox.setVgrow(graph, Priority.ALWAYS);
        getChildren().addAll(header, bars, graph);

        model.revisionProperty().addListener((o, a, b) -> refresh());
        windowBox.valueProperty().addListener((o, a, b) -> refresh());
        refresh();
    }

    private void refresh() {
        Message.Snapshot newest = Metrics.newest(model.history());
        bars.setRows(Usage.memory(newest));

        long total = newest != null && newest.mem() != null ? newest.mem().totalBytes() : 0;
        AxisScale.YAxis axis = total > 0 ? AxisScale.fixed(total, Units.unitIndex(total)) : AxisScale.fixed(1, 0);

        ChartSeries.Chart chart = ChartSeries.build(model.history(), windowBox.seconds(), MAX_POINTS, EXTRACTORS);
        graph.setData(chart, axis, v -> Units.scaled(v, axis.unitIndex(), false));
        graph.setOverlay(StatusText.overlay(model.connectionState(), !chart.isEmpty()));

        ChartSeries.Stats st = chart.lines().get(0).stats();
        stats.setText(chart.isEmpty() ? "no data yet"
                : String.format(Locale.ROOT, "in use %s    avg %s    peak %s",
                        Units.bytes(st.latest()), Units.bytes(st.average()), Units.bytes(st.max())));
    }
}
