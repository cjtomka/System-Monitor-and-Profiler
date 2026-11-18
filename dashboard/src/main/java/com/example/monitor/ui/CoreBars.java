package com.example.monitor.ui;

import com.example.monitor.protocol.Message;
import java.util.List;
import java.util.Locale;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/** One vertical bar per logical core, colored by load. FX thread only. */
final class CoreBars extends Region {
    private final Canvas canvas = new Canvas();
    private final Font font = Font.font("Segoe UI", 11);
    private List<Double> values = List.of();

    CoreBars() {
        getChildren().add(canvas);
        setMinHeight(96);
        setPrefHeight(120);
    }

    void setSnapshot(Message.Snapshot s) {
        values = (s == null || s.cpu() == null || s.cpu().coresPct() == null) ? List.of() : s.cpu().coresPct();
        draw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(Math.max(0, getWidth()));
        canvas.setHeight(Math.max(0, getHeight()));
        draw();
    }

    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(Theme.PLOT_BG);
        g.fillRect(0, 0, w, h);
        if (w < 60 || h < 50) return;

        g.setFont(font);
        g.setFill(Theme.AXIS_TEXT);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.fillText("Per-core load", 10, 6);

        int n = values.size();
        if (n == 0) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText("Per-core data unavailable", w / 2, h / 2 + 8);
            return;
        }

        double left = 10, right = 10, top = 26, bottom = 20;
        double aw = w - left - right, ah = h - top - bottom;
        if (aw < n || ah < 10) return;

        double gap = n > 32 ? 1 : 3;
        double bw = (aw - gap * (n - 1)) / n;
        int labelEvery = bw >= 18 ? 1 : (int) Math.ceil(18.0 / (bw + gap));

        for (int i = 0; i < n; i++) {
            Double v = values.get(i);                                   // null when the daemon could not read it
            double pct = v == null || v.isNaN() ? 0.0 : Math.max(0.0, Math.min(100.0, v));
            double x = left + i * (bw + gap);

            g.setFill(Theme.BAR_TRACK);
            g.fillRect(x, top, bw, ah);
            double fillH = ah * pct / 100.0;
            g.setFill(Heat.color(pct));
            g.fillRect(x, top + ah - fillH, bw, fillH);

            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.TOP);
            if (i % labelEvery == 0) {
                g.setFill(Theme.AXIS_TEXT);
                g.fillText(String.valueOf(i), x + bw / 2, top + ah + 4);
            }
            if (bw >= 28) {
                g.setFill(Theme.VALUE_TEXT);
                g.fillText(String.format(Locale.ROOT, "%.0f", pct), x + bw / 2, top + 2);
            }
        }
    }
}
