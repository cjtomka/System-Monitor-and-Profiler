package com.example.monitor.ui;

import javafx.scene.control.ComboBox;

/** The "how much history to show" drop-down shared by the graph tabs. */
final class WindowSelector extends ComboBox<WindowSelector.Choice> {
    record Choice(String label, double seconds) {
        @Override public String toString() { return label; }
    }

    WindowSelector() {
        getItems().addAll(new Choice("Last 1 min", 60), new Choice("Last 5 min", 300),
                new Choice("Whole buffer", 0));
        getSelectionModel().select(0);
    }

    /** Zero means the whole buffer. */
    double seconds() {
        Choice c = getValue();
        return c == null ? 60 : c.seconds();
    }
}
