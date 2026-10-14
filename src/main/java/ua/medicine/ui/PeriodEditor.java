package ua.medicine.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;
import ua.medicine.ui.MedicineForm.PeriodRow;

import java.time.LocalDate;

/** One row of the medicine dialog's «Періоди прийому» section. */
final class PeriodEditor extends VBox {

    private final DatePicker start = new DatePicker();
    private final RadioButton fixed = new RadioButton(Messages.get("duration.fixed"));
    private final RadioButton ongoing = new RadioButton(Messages.get("duration.ongoing"));
    private final Spinner<Integer> amount = new Spinner<>(1, 999, 10);
    private final ComboBox<PeriodUnit> unit = new ComboBox<>(FXCollections.observableArrayList(PeriodUnit.values()));
    private final Label endLabel = new Label();
    final Button remove = new Button("✕");
    final Button endToday = new Button(Messages.get("btn.endToday"));

    /** @param onChange called after any edit, so the dialog can update its buttons */
    PeriodEditor(PeriodRow row, Runnable onChange) {
        super(2);
        getStyleClass().add("period-row");
        var durationGroup = new ToggleGroup();
        fixed.setToggleGroup(durationGroup);
        ongoing.setToggleGroup(durationGroup);
        unit.setConverter(new StringConverter<>() {
            @Override
            public String toString(PeriodUnit u) {
                return u == null ? "" : Messages.get("unit." + u.name().toLowerCase());
            }

            @Override
            public PeriodUnit fromString(String s) {
                return null;
            }
        });
        unit.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(PeriodUnit item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : unit.getConverter().toString(item));
            }
        });
        amount.setEditable(true);
        amount.setPrefWidth(80);
        start.setPrefWidth(140);
        unit.setPrefWidth(110);
        endLabel.getStyleClass().add("muted");

        set(row);
        durationGroup.selectedToggleProperty().addListener((o, a, b) -> changed(onChange));
        amount.valueProperty().addListener((o, a, b) -> changed(onChange));
        unit.valueProperty().addListener((o, a, b) -> changed(onChange));
        start.valueProperty().addListener((o, a, b) -> changed(onChange));

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var line = new HBox(8, start, fixed, ongoing, spacer, remove);
        line.setAlignment(Pos.CENTER_LEFT);
        var durationLine = new HBox(8, amount, unit, endLabel, endToday);
        durationLine.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(line, durationLine);
        update();
    }

    PeriodRow row() {
        return new PeriodRow(start.getValue(), ongoing.isSelected(), amount.getValue(), unit.getValue());
    }

    void set(PeriodRow row) {
        start.setValue(row.start());
        (row.ongoing() ? ongoing : fixed).setSelected(true);
        if (row.amount() != null) {
            amount.getValueFactory().setValue(row.amount());
        }
        unit.setValue(row.unit());
        update();
    }

    private void changed(Runnable onChange) {
        update();
        onChange.run();
    }

    private void update() {
        boolean isFixed = fixed.isSelected();
        amount.setDisable(!isFixed);
        unit.setDisable(!isFixed);
        if (!isFixed || start.getValue() == null || unit.getValue() == null || amount.getValue() == null) {
            endLabel.setText("");
            return;
        }
        LocalDate end = start.getValue().plus(amount.getValue(), unit.getValue().chronoUnit()).minusDays(1);
        endLabel.setText(Messages.get("field.end", Formats.date(end)) + "  ("
                + Formats.amount(amount.getValue(), unit.getValue()) + ")");
    }
}
