package com.example.monitor.ui;

import javafx.scene.paint.Color;

final class Theme {
    private Theme() {}

    static final Color PAGE_BG       = Color.web("#f4f6f8");
    static final Color PLOT_BG       = Color.WHITE;
    static final Color GRID          = Color.web("#e4e8ec");
    static final Color AXIS_TEXT     = Color.web("#5f6b7a");
    static final Color VALUE_TEXT    = Color.web("#24292f");
    static final Color LINE          = Color.web("#1f6feb");     // CPU, disk reads
    static final Color MEMORY        = Color.web("#8250df");
    static final Color WRITE         = Color.web("#d1632a");     // disk writes
    static final Color BAR_TRACK     = Color.web("#edf0f3");
    static final Color BANNER_BG     = Color.web("#fff4ce");
    static final Color BANNER_BORDER = Color.web("#e5c36a");
    static final Color BANNER_TEXT   = Color.web("#6b5200");

    static final Color OK   = Color.web("#2da44e");
    static final Color WARN = Color.web("#d4a72c");
    static final Color BAD  = Color.web("#cf222e");
    static final Color IDLE = Color.web("#8c959f");
}
