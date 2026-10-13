package ua.medicine.ui;

import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import ua.medicine.AppContext;
import ua.medicine.domain.DoseStatus;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;
import ua.medicine.service.HistoryFilter;
import ua.medicine.service.HistoryRow;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Non-modal history window: filters, adherence tiles and the dose table. */
public final class HistoryController {

    private record Choice<T>(T value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Messages.LOCALE);

    private final AppContext ctx;
    private final ComboBox<Choice<Long>> person = new ComboBox<>();
    private final ComboBox<Choice<Long>> medicine = new ComboBox<>();
    private final ComboBox<Choice<Integer>> period = new ComboBox<>();
    private final FlowPane tiles = new FlowPane(10, 10);
    private final TableView<HistoryRow> table = new TableView<>();

    private HistoryController(AppContext ctx) {
        this.ctx = ctx;
    }

    public static void show(Window owner, AppContext ctx) {
        var controller = new HistoryController(ctx);
        var stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(Messages.get("history.title"));
        var scene = new Scene(controller.build(), 900, 640);
        scene.getStylesheets().add(HistoryController.class.getResource("theme.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private VBox build() {
        List<Choice<Long>> persons = new ArrayList<>();
        persons.add(new Choice<>(null, Messages.get("history.allPersons")));
        ctx.personService().all().forEach(p -> persons.add(new Choice<>(p.id(), p.name())));
        person.setItems(FXCollections.observableArrayList(persons));
        person.getSelectionModel().selectFirst();

        List<Choice<Long>> medicines = new ArrayList<>();
        medicines.add(new Choice<>(null, Messages.get("history.allMedicines")));
        ctx.medicineRepository().findAll().forEach(m -> medicines.add(new Choice<>(m.id(), m.name())));
        medicine.setItems(FXCollections.observableArrayList(medicines));
        medicine.getSelectionModel().selectFirst();

        period.setItems(FXCollections.observableArrayList(
                new Choice<>(7, Messages.get("history.days7")),
                new Choice<>(30, Messages.get("history.days30")),
                new Choice<>(90, Messages.get("history.days90")),
                new Choice<>(null, Messages.get("history.all"))));
        period.getSelectionModel().select(1);

        person.setOnAction(e -> reload());
        medicine.setOnAction(e -> reload());
        period.setOnAction(e -> reload());

        table.getColumns().addAll(List.of(
                column("history.col.time", r -> DATE_TIME.format(r.scheduledAt())),
                column("history.col.person", HistoryRow::personName),
                column("history.col.medicine", HistoryRow::medicineName),
                statusColumn(),
                column("history.col.takenAt", r -> r.takenAt() == null ? "—" : takenAtText(r))));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        var title = new Label(Messages.get("history.title"));
        title.getStyleClass().add("app-title");
        var filters = new HBox(8, title, person, medicine, period);
        filters.setAlignment(Pos.CENTER_LEFT);
        var root = new VBox(12, filters, tiles, table);
        root.setPadding(new Insets(16));
        root.getStyleClass().add("app-root");
        reload();
        return root;
    }

    private void reload() {
        var filter = new HistoryFilter(person.getValue().value(), medicine.getValue().value(), period.getValue().value());
        try {
            tiles.getChildren().clear();
            ctx.historyService().adherence(filter).forEach((m, adherence) -> {
                var name = new Label(m.name());
                name.getStyleClass().add("muted");
                var percent = new Label(adherence.percent().isPresent() ? adherence.percent().getAsInt() + "%" : "—");
                percent.getStyleClass().add("history-percent");
                var tile = new VBox(2, name, percent);
                tile.getStyleClass().add("history-tile");
                tiles.getChildren().add(tile);
            });
            table.setItems(FXCollections.observableArrayList(ctx.historyService().rows(filter)));
        } catch (RuntimeException e) {
            Dialogs.dbError(table.getScene() == null ? null : table.getScene().getWindow(), e);
        }
    }

    private static String takenAtText(HistoryRow r) {
        LocalDateTime taken = r.takenAt();
        return taken.toLocalDate().equals(r.scheduledAt().toLocalDate())
                ? Formats.time(taken.toLocalTime())
                : DATE_TIME.format(taken);
    }

    private static TableColumn<HistoryRow, String> column(String key, Function<HistoryRow, String> value) {
        var column = new TableColumn<HistoryRow, String>(Messages.get(key));
        column.setCellValueFactory(c -> new SimpleObjectProperty<>(value.apply(c.getValue())));
        return column;
    }

    private static TableColumn<HistoryRow, DoseStatus> statusColumn() {
        var column = new TableColumn<HistoryRow, DoseStatus>(Messages.get("history.col.status"));
        column.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().status()));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(DoseStatus status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-taken", "status-missed", "status-overdue");
                if (empty || status == null) {
                    setText(null);
                    return;
                }
                setText(Messages.get("status." + status.name().toLowerCase()));
                getStyleClass().add("status-" + status.name().toLowerCase());
            }
        });
        return column;
    }
}
