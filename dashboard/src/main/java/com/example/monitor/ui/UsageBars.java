package com.example.monitor.ui;

import com.example.monitor.viewmodel.Usage;
import java.util.List;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/** Horizontal bars with a label on the left and a detail text on the right. FX thread only. */
final class UsageBars extends Region {
    private static final double ROW_H = 28, PAD = 6;

    private final Canvas canvas = new Canvas();
    private final Font font = Font.font("Segoe UI", 12);
    private final Text probe = new Text();
    private List<Usage.Row> rows = List.of();

    UsageBars() {
        getChildren().add(canvas);
        applyHeight();
    }

    void setRows(List<Usage.Row> rows) {
        boolean resize = rows.size() != this.rows.size();
        this.rows = rows;
        if (resize) applyHeight();
        draw();
    }

    private void applyHeight() {
        double h = Math.max(ROW_H, rows.size() * ROW_H + 2 * PAD);
        setMinHeight(h);
        setPrefHeight(h);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(Math.max(0, getWidth()));
        canvas.setHeight(Math.max(0, getHeight()));
        draw();
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
        g.setFont(font);
        g.setTextBaseline(VPos.CENTER);

        if (rows.isEmpty()) {
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(Theme.AXIS_TEXT);
            g.fillText("No data yet", 10, h / 2);
            return;
        }

        double labelW = 0, detailW = 0;
        for (Usage.Row r : rows) {
            labelW = Math.max(labelW, textWidth(r.label()));
            detailW = Math.max(detailW, textWidth(r.detail()));
        }
        double barL = 10 + labelW + 12;
        double barR = w - 10 - detailW - 12;
        boolean showDetail = barR - barL >= 50;
        if (!showDetail) barR = w - 10;
        if (barR - barL < 10) return;

        for (int i = 0; i < rows.size(); i++) {
            Usage.Row r = rows.get(i);
            double cy = PAD + i * ROW_H + ROW_H / 2;
            double bh = 14;

            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(Theme.AXIS_TEXT);
            g.fillText(r.label(), 10, cy);

            g.setFill(Theme.BAR_TRACK);
            g.fillRoundRect(barL, cy - bh / 2, barR - barL, bh, 6, 6);
            double fillW = (barR - barL) * r.fraction();
            if (fillW > 0.5) {
                g.setFill(Heat.color(r.fraction() * 100.0));
                g.fillRoundRect(barL, cy - bh / 2, Math.max(fillW, 6), bh, 6, 6);
            }

            if (showDetail) {
                g.setTextAlign(TextAlignment.RIGHT);
                g.setFill(Theme.VALUE_TEXT);
                g.fillText(r.detail(), w - 10, cy);
            }
        }
    }
}
