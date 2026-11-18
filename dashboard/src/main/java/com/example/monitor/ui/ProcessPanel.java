package com.example.monitor.ui;

import com.example.monitor.model.MetricsModel;
import com.example.monitor.protocol.Message;
import com.example.monitor.viewmodel.ProcRow;
import com.example.monitor.viewmodel.ProcRows;
import com.example.monitor.viewmodel.Units;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** The processes the daemon reports: busiest by CPU, then largest by memory. Click a header to sort. */
final class ProcessPanel extends VBox {
    private final MetricsModel model;
    private final ProcRows rows = new ProcRows();
    private final Label info = new Label();

    @SuppressWarnings("deprecation")
    ProcessPanel(MetricsModel model) {
        this.model = model;
        setSpacing(8);
        setPadding(new Insets(10));

        Label title = new Label("Processes");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        info.setStyle("-fx-text-fill: #5f6b7a;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, info, spacer);
        header.setAlignment(Pos.CENTER_LEFT);

        TableView<ProcRow> table = new TableView<>();
        TableColumn<ProcRow, String> nameCol = new TableColumn<>("Process");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        TableColumn<ProcRow, Number> pidCol = new TableColumn<>("PID");
        pidCol.setCellValueFactory(c -> new ReadOnlyLongWrapper(c.getValue().pid()));
        TableColumn<ProcRow, Number> cpuCol = new TableColumn<>("CPU %");
        cpuCol.setCellValueFactory(c -> c.getValue().cpuPctProperty());
        cpuCol.setCellFactory(col -> numberCell(v -> String.format(Locale.ROOT, "%.1f", v.doubleValue())));
        TableColumn<ProcRow, Number> memCol = new TableColumn<>("Memory");
        memCol.setCellValueFactory(c -> c.getValue().rssBytesProperty());
        memCol.setCellFactory(col -> numberCell(v -> Units.bytes(v.doubleValue())));

        table.getColumns().add(nameCol);
        table.getColumns().add(pidCol);
        table.getColumns().add(cpuCol);
        table.getColumns().add(memCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No process data from the daemon yet"));

        // Rows are updated in place by PID, and the SortedList re-sorts as their values change.
        SortedList<ProcRow> sorted = new SortedList<>(rows.rows());
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        cpuCol.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(cpuCol);

        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(header, table);

        model.revisionProperty().addListener((o, a, b) -> refresh());
        refresh();
    }

    private static TableCell<ProcRow, Number> numberCell(Function<Number, String> format) {
        return new TableCell<>() {
            { setStyle("-fx-alignment: CENTER-RIGHT;"); }

            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.apply(item));
            }
        };
    }

    private void refresh() {
        Message.Snapshot s = model.latest();                    // the newest LIVE snapshot: only live ones carry processes
        rows.sync(s == null ? null : s.topProcs());
        info.setText(rows.rows().isEmpty() ? ""
                : rows.rows().size() + " listed by the daemon: busiest by CPU, then largest by memory");
    }
}
