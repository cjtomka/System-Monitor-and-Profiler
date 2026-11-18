package com.example.monitor.ui;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.viewmodel.AxisScale;
import com.example.monitor.viewmodel.ChartSeries;
import com.example.monitor.viewmodel.Metrics;
import com.example.monitor.viewmodel.StatusText;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** The CPU view: header with live stats, the history graph, and the per-core strip. */
final class CpuPanel extends VBox {
    private static final int MAX_POINTS = 1500;
    private static final List<ChartSeries.Extractor> EXTRACTORS = List.of(Metrics.cpuTotal());
    private static final AxisScale.YAxis AXIS = AxisScale.fixed(100, 0);

    private final MetricsModel model;
    private final LineGraph graph = new LineGraph();
    private final CoreBars bars = new CoreBars();
    private final Label stats = new Label();
    private final WindowSelector windowBox = new WindowSelector();

    CpuPanel(MetricsModel model) {
        this.model = model;
        setSpacing(8);
        setPadding(new Insets(10));

        Label title = new Label("CPU");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        stats.setStyle("-fx-text-fill: #5f6b7a;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, stats, spacer, windowBox);
        header.setAlignment(Pos.CENTER_LEFT);

        graph.setStyles(List.of(new LineGraph.LineStyle(Theme.LINE, true)));
        VBox.setVgrow(graph, Priority.ALWAYS);
        getChildren().addAll(header, graph, bars);

        model.revisionProperty().addListener((o, a, b) -> refresh());     // once per frame that changed anything
        windowBox.valueProperty().addListener((o, a, b) -> refresh());
        refresh();
    }

    private void refresh() {
        ChartSeries.Chart chart = ChartSeries.build(model.history(), windowBox.seconds(), MAX_POINTS, EXTRACTORS);
        graph.setData(chart, AXIS, v -> Math.round(v) + "%");
        graph.setOverlay(StatusText.overlay(model.connectionState(), !chart.isEmpty()));
        bars.setSnapshot(model.latest());

        ChartSeries.Stats st = chart.lines().get(0).stats();
        stats.setText(chart.isEmpty() ? "no data yet"
                : String.format(Locale.ROOT, "%.1f%%    avg %.1f%%    max %.1f%%", st.latest(), st.average(), st.max()));
    }
}