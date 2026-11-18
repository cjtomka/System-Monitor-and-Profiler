package com.example.monitor.ui;

import com.example.monitor.viewmodel.AxisScale;
import com.example.monitor.viewmodel.ChartSeries;
import com.example.monitor.viewmodel.TimeAxis;
import java.util.List;
import java.util.function.Function;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * A line graph on a Canvas: any number of lines, a legend when there is more than one, and a
 * y-axis described by the caller. It only paints what the view model describes. FX thread only.
 */
final class LineGraph extends Region {
    record LineStyle(Color color, boolean fill) {}

    private static final LineStyle DEFAULT_STYLE = new LineStyle(Theme.LINE, true);
    private static final double RIGHT = 14, TOP = 10, BOTTOM = 24, MIN_LEFT = 46;

    private final Canvas canvas = new Canvas();
    private final Font font = Font.font("Segoe UI", 11);
    private final Text probe = new Text();

    private ChartSeries.Chart chart = new ChartSeries.Chart(List.of(), 60);
    private AxisScale.YAxis axis = AxisScale.fixed(100, 0);
    private Function<Double, String> tickLabel = v -> "";
    private List<LineStyle> styles = List.of(DEFAULT_STYLE);
    private String overlay = "";

    LineGraph() {
        getChildren().add(canvas);
        setMinHeight(140);
        setPrefHeight(260);
    }

    /** One style per line, in order; the last one repeats if there are more lines than styles. */
    void setStyles(List<LineStyle> styles) {
        if (styles.isEmpty()) throw new IllegalArgumentException("need at least one style");
        this.styles = styles;
    }

    void setData(ChartSeries.Chart chart, AxisScale.YAxis axis, Function<Double, String> tickLabel) {
        this.chart = chart;
        this.axis = axis;
        this.tickLabel = tickLabel;
        draw();
    }

    void setOverlay(String text) {
        this.overlay = text == null ? "" : text;
        draw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(Math.max(0, getWidth()));
        canvas.setHeight(Math.max(0, getHeight()));
        draw();
    }

    private LineStyle styleFor(int i) {
        return styles.get(Math.min(i, styles.size() - 1));
    }

    private double textWidth(String s) {
        probe.setFont(font);
        probe.setText(s);
        return probe.getLayoutBounds().getWidth();
    }

    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(Theme.PLOT_BG);
        g.fillRect(0, 0, w, h);
        if (w < 120 || h < 80) return;

        double maxLabel = 0;
        for (double t : axis.ticks()) maxLabel = Math.max(maxLabel, textWidth(tickLabel.apply(t)));
        double left = Math.max(MIN_LEFT, maxLabel + 14);
        double pw = w - left - RIGHT, ph = h - TOP - BOTTOM;
        if (pw < 40) return;

        double window = chart.windowSeconds();
        double ymax = axis.max();
        g.setFont(font);
        g.setLineWidth(1);

        // y axis: gridlines and labels
        g.setTextAlign(TextAlignment.RIGHT);
        g.setTextBaseline(VPos.CENTER);
        for (double t : axis.ticks()) {
            double y = TOP + ph * (1 - t / ymax);
            g.setStroke(Theme.GRID);
            g.strokeLine(left, Math.round(y) + 0.5, left + pw, Math.round(y) + 0.5);
            g.setFill(Theme.AXIS_TEXT);
            g.fillText(tickLabel.apply(t), left - 6, y);
        }

        // x axis: ticks counted back from the newest snapshot
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.TOP);
        for (double ago : TimeAxis.ticks(window)) {
            double x = left + pw * (1 - ago / window);
            g.setStroke(Theme.GRID);
            g.strokeLine(Math.round(x) + 0.5, TOP, Math.round(x) + 0.5, TOP + ph);
            g.setFill(Theme.AXIS_TEXT);
            g.fillText(TimeAxis.formatAgo(ago), x, TOP + ph + 6);
        }

        // data, clipped to the plot area
        g.save();
        g.beginPath();
        g.rect(left, TOP, pw, ph);
        g.clip();
        double base = TOP + ph;
        for (int i = 0; i < chart.lines().size(); i++) {
            LineStyle st = styleFor(i);
            for (List<ChartSeries.Point> seg : chart.lines().get(i).segments()) {
                if (seg.size() == 1) {
                    g.setFill(st.color());
                    g.fillOval(px(seg.get(0).x(), window, left, pw) - 2, py(seg.get(0).y(), ymax, ph) - 2, 4, 4);
                    continue;
                }
                if (st.fill()) {
                    g.beginPath();
                    g.moveTo(px(seg.get(0).x(), window, left, pw), base);
                    for (ChartSeries.Point p : seg) g.lineTo(px(p.x(), window, left, pw), py(p.y(), ymax, ph));
                    g.lineTo(px(seg.get(seg.size() - 1).x(), window, left, pw), base);
                    g.closePath();
                    g.setFill(st.color().deriveColor(0, 1, 1, 0.14));
                    g.fill();
                }
                g.beginPath();
                g.moveTo(px(seg.get(0).x(), window, left, pw), py(seg.get(0).y(), ymax, ph));
                for (int k = 1; k < seg.size(); k++) {
                    g.lineTo(px(seg.get(k).x(), window, left, pw), py(seg.get(k).y(), ymax, ph));
                }
                g.setStroke(st.color());
                g.setLineWidth(1.6);
                g.stroke();
            }
        }
        g.restore();

        if (chart.lines().size() > 1) drawLegend(g, left + 10, TOP + 12);
        if (!overlay.isEmpty()) drawBanner(g, left, pw, ph);
    }

    private static double px(double x, double window, double left, double pw) {
        return left + pw * (x + window) / window;
    }

    private static double py(double value, double ymax, double ph) {
        return TOP + ph * (1 - value / ymax);
    }

    private void drawLegend(GraphicsContext g, double x0, double y) {
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        double x = x0;
        for (int i = 0; i < chart.lines().size(); i++) {
            String name = chart.lines().get(i).name();
            g.setStroke(styleFor(i).color());
            g.setLineWidth(2.2);
            g.strokeLine(x, y, x + 14, y);
            g.setFill(Theme.AXIS_TEXT);
            g.fillText(name, x + 19, y);
            x += 19 + textWidth(name) + 16;
        }
        g.setLineWidth(1);
    }

    private void drawBanner(GraphicsContext g, double left, double pw, double ph) {
        double bw = textWidth(overlay) + 28, bh = 26;
        double x = left + (pw - bw) / 2;
        double y = chart.isEmpty() ? TOP + (ph - bh) / 2 : TOP + 8;
        g.setFill(Theme.BANNER_BG);
        g.fillRoundRect(x, y, bw, bh, 8, 8);
        g.setStroke(Theme.BANNER_BORDER);
        g.setLineWidth(1);
        g.strokeRoundRect(x, y, bw, bh, 8, 8);
        g.setFill(Theme.BANNER_TEXT);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(overlay, x + bw / 2, y + bh / 2);
    }
}
