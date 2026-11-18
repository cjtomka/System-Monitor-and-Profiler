package com.example.monitor.ui;

import javafx.scene.paint.Color;

/** Load to color: green at 0%, through yellow, to red at 100%. */
public final class Heat {
    private Heat() {}

    public static Color color(double pct) {
        double p = Double.isNaN(pct) ? 0.0 : Math.max(0.0, Math.min(100.0, pct));
        return Color.hsb(120.0 * (1.0 - p / 100.0), 0.70, 0.85);
    }
}
