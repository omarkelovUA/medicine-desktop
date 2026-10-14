package ua.medicine.ui;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import ua.medicine.AppContext;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Person;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Add / edit medicine dialog. */
public final class MedicineDialogController {

    private static final ButtonType DELETE = new ButtonType(Messages.get("btn.delete"), ButtonBar.ButtonData.LEFT);

    private final AppContext ctx;
    private final Medicine existing;

    private final ComboBox<Person> person = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField dosage = new TextField();
    private final ColorPicker color;
    private final DatePicker start = new DatePicker();
    private final RadioButton fixed = new RadioButton(Messages.get("duration.fixed"));
    private final RadioButton ongoing = new RadioButton(Messages.get("duration.ongoing"));
    private final Spinner<Integer> amount = new Spinner<>(1, 999, 10);
    private final ComboBox<PeriodUnit> unit = new ComboBox<>(FXCollections.observableArrayList(PeriodUnit.values()));
    private final Label endLabel = new Label();
    private final RadioButton daily = new RadioButton(Messages.get("freq.daily"));
    private final RadioButton weekly = new RadioButton(Messages.get("freq.weekly"));
    private final RadioButton monthly = new RadioButton(Messages.get("freq.monthly"));
    private final Map<DayOfWeek, ToggleButton> weekdayButtons = new EnumMap<>(DayOfWeek.class);
    private final HBox weekdaysRow = new HBox(4);
    private final Spinner<Integer> dayOfMonth = new Spinner<>(1, 31, 1);
    private final HBox dayOfMonthRow;
    private final List<LocalTime> times = new ArrayList<>();
    private final FlowPane timesPane = new FlowPane(6, 6);
    private final Spinner<Integer> hour = new Spinner<>(0, 23, 8);
    private final Spinner<Integer> minute = new Spinner<>(0, 59, 0, 5);
    private final Map<String, Label> errors = Map.of(
            "name", errorLabel(), "amount", errorLabel(), "weekdays", errorLabel(), "times", errorLabel());

    private MedicineDialogController(AppContext ctx, Medicine existing, Long preselectedPersonId) {
        this.ctx = ctx;
        this.existing = existing;
        MedicineForm form = existing != null ? MedicineForm.from(existing) : null;
        color = new ColorPicker(form != null ? form.color() : null);

        List<Person> persons = ctx.personService().all();
        person.setItems(FXCollections.observableArrayList(persons));
        person.setConverter(new StringConverter<>() {
            @Override
            public String toString(Person p) {
                return p == null ? "" : p.name();
            }

            @Override
            public Person fromString(String s) {
                return null;
            }
        });
        Long personId = form != null ? form.personId() : preselectedPersonId;
        persons.stream().filter(p -> p.id().equals(personId)).findFirst()
                .or(() -> persons.stream().findFirst())
                .ifPresent(person::setValue);
        // moving a medicine to another person would leave its events in the old calendar
        person.setDisable(existing != null);

        var durationGroup = new ToggleGroup();
        fixed.setToggleGroup(durationGroup);
        ongoing.setToggleGroup(durationGroup);
        var frequencyGroup = new ToggleGroup();
        daily.setToggleGroup(frequencyGroup);
        weekly.setToggleGroup(frequencyGroup);
        monthly.setToggleGroup(frequencyGroup);

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
        dayOfMonth.setEditable(true);
        dayOfMonth.setPrefWidth(80);
        hour.setPrefWidth(70);
        minute.setPrefWidth(70);
        hour.setEditable(true);
        minute.setEditable(true);

        for (DayOfWeek day : DayOfWeek.values()) {
            var button = new ToggleButton(day.getDisplayName(TextStyle.SHORT, Messages.LOCALE));
            weekdayButtons.put(day, button);
            weekdaysRow.getChildren().add(button);
        }
        dayOfMonthRow = new HBox(8, new Label(Messages.get("field.dayOfMonth")), dayOfMonth);
        dayOfMonthRow.setAlignment(Pos.CENTER_LEFT);

        if (form != null) {
            name.setText(form.name());
            dosage.setText(form.dosage());
            MedicineForm.PeriodRow row = form.periods().getLast();
            start.setValue(row.start());
            (row.ongoing() ? ongoing : fixed).setSelected(true);
            if (row.amount() != null) {
                amount.getValueFactory().setValue(row.amount());
            }
            unit.setValue(row.unit());
            switch (form.frequency()) {
                case MedicineForm.WEEKLY -> weekly.setSelected(true);
                case MedicineForm.MONTHLY -> monthly.setSelected(true);
                default -> daily.setSelected(true);
            }
            form.weekdays().forEach(d -> weekdayButtons.get(d).setSelected(true));
            dayOfMonth.getValueFactory().setValue(form.dayOfMonth());
            times.addAll(form.times());
        } else {
            start.setValue(LocalDate.now(ctx.clock()));
            fixed.setSelected(true);
            unit.setValue(PeriodUnit.DAYS);
            daily.setSelected(true);
            times.add(LocalTime.of(8, 0));
        }

        durationGroup.selectedToggleProperty().addListener((o, a, b) -> updateVisibility());
        frequencyGroup.selectedToggleProperty().addListener((o, a, b) -> updateVisibility());
        amount.valueProperty().addListener((o, a, b) -> updateEndLabel());
        unit.valueProperty().addListener((o, a, b) -> updateEndLabel());
        start.valueProperty().addListener((o, a, b) -> updateEndLabel());
        renderTimes();
        updateVisibility();
    }

    /** Shows the dialog; returns the saved medicine, or empty if cancelled or deleted. */
    public static Optional<Medicine> show(Window owner, AppContext ctx, Medicine existing, Long preselectedPersonId) {
        return new MedicineDialogController(ctx, existing, preselectedPersonId).show(owner);
    }

    private Optional<Medicine> show(Window owner) {
        var dialog = new Dialog<Medicine>();
        dialog.initOwner(owner);
        dialog.setTitle(Messages.get(existing == null ? "dialog.medicine.new" : "dialog.medicine.edit"));
        dialog.setHeaderText(null);
        var pane = dialog.getDialogPane();
        var save = new ButtonType(Messages.get("btn.save"), ButtonBar.ButtonData.OK_DONE);
        var cancel = new ButtonType(Messages.get("btn.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(save, cancel);
        if (existing != null) {
            pane.getButtonTypes().add(DELETE);
        }
        pane.setContent(content());

        Medicine[] result = new Medicine[1];
        pane.lookupButton(save).addEventFilter(ActionEvent.ACTION, e -> {
            if (!trySave()) {
                e.consume();
            } else {
                result[0] = savedMedicine;
            }
        });
        if (existing != null) {
            pane.lookupButton(DELETE).addEventFilter(ActionEvent.ACTION, e -> {
                if (!Dialogs.confirm(pane.getScene().getWindow(),
                        Messages.get("confirm.deleteMedicine", existing.name()))) {
                    e.consume();
                    return;
                }
                try {
                    ctx.medicineService().delete(existing.id());
                } catch (RuntimeException ex) {
                    Dialogs.dbError(pane.getScene().getWindow(), ex);
                }
            });
        }
        dialog.setResultConverter(button -> button == save ? result[0] : null);
        return dialog.showAndWait();
    }

    private Medicine savedMedicine;

    private boolean trySave() {
        MedicineForm form = form();
        Map<String, String> problems = form.validate();
        errors.forEach((field, label) -> {
            String key = problems.get(field);
            label.setText(key == null ? "" : Messages.get(key));
            label.setVisible(key != null);
            label.setManaged(key != null);
        });
        if (!problems.isEmpty() || form.personId() == null) {
            name.getScene().getWindow().sizeToScene();
            return false;
        }
        try {
            savedMedicine = existing == null
                    ? ctx.medicineService().create(form.toMedicine(null, true))
                    : ctx.medicineService().update(form.toMedicine(existing.id(), existing.active()));
            return true;
        } catch (RuntimeException e) {
            Dialogs.dbError(name.getScene().getWindow(), e);
            return false;
        }
    }

    private MedicineForm form() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        weekdayButtons.forEach((day, button) -> {
            if (button.isSelected()) {
                days.add(day);
            }
        });
        String frequency = weekly.isSelected() ? MedicineForm.WEEKLY
                : monthly.isSelected() ? MedicineForm.MONTHLY : MedicineForm.DAILY;
        Person p = person.getValue();
        return new MedicineForm(p == null ? null : p.id(), name.getText(), dosage.getText(), color.selected(),
                List.of(new MedicineForm.PeriodRow(start.getValue() != null ? start.getValue() : LocalDate.now(ctx.clock()),
                        ongoing.isSelected(), amount.getValue(), unit.getValue())),
                frequency, days, dayOfMonth.getValue(), List.copyOf(times));
    }

    private Node content() {
        var durationRow = new HBox(8, fixed, ongoing, amount, unit);
        durationRow.setAlignment(Pos.CENTER_LEFT);
        var frequencyRow = new HBox(12, daily, weekly, monthly);
        var addTime = new Button(Messages.get("btn.addTime"));
        addTime.setOnAction(e -> {
            times.add(LocalTime.of(hour.getValue(), minute.getValue()));
            renderTimes();
        });
        var timeEntry = new HBox(6, hour, new Label(":"), minute, addTime);
        timeEntry.setAlignment(Pos.CENTER_LEFT);

        var box = new VBox(4,
                formLabel("field.person"), person,
                formLabel("field.name"), name, errors.get("name"),
                formLabel("field.dosage"), dosage,
                formLabel("field.color"), color,
                formLabel("field.start"), start,
                formLabel("field.duration"), durationRow, endLabel, errors.get("amount"),
                formLabel("field.frequency"), frequencyRow, weekdaysRow, dayOfMonthRow, errors.get("weekdays"),
                formLabel("field.times"), timesPane, timeEntry, errors.get("times"));
        box.setPadding(new Insets(8));
        box.setPrefWidth(440);
        errors.values().forEach(l -> {
            l.setVisible(false);
            l.setManaged(false);
        });
        return box;
    }

    private void renderTimes() {
        times.sort(null);
        timesPane.getChildren().clear();
        for (LocalTime time : List.copyOf(times)) {
            var chip = new Button(Formats.time(time) + "  ✕");
            chip.getStyleClass().add("dose-chip");
            chip.setOnAction(e -> {
                times.remove(time);
                renderTimes();
            });
            timesPane.getChildren().add(chip);
        }
    }

    private void updateVisibility() {
        boolean isFixed = fixed.isSelected();
        amount.setDisable(!isFixed);
        unit.setDisable(!isFixed);
        endLabel.setVisible(isFixed);
        endLabel.setManaged(isFixed);
        weekdaysRow.setVisible(weekly.isSelected());
        weekdaysRow.setManaged(weekly.isSelected());
        dayOfMonthRow.setVisible(monthly.isSelected());
        dayOfMonthRow.setManaged(monthly.isSelected());
        updateEndLabel();
    }

    private void updateEndLabel() {
        if (start.getValue() == null || unit.getValue() == null || amount.getValue() == null) {
            endLabel.setText("");
            return;
        }
        LocalDate end = start.getValue().plus(amount.getValue(), unit.getValue().chronoUnit()).minusDays(1);
        endLabel.setText(Messages.get("field.end", Formats.date(end)) + "  (" + Formats.amount(amount.getValue(), unit.getValue()) + ")");
        endLabel.getStyleClass().setAll("label", "muted");
    }

    private static Label formLabel(String key) {
        var label = new Label(Messages.get(key).toUpperCase(Messages.LOCALE));
        label.getStyleClass().add("form-label");
        VBox.setMargin(label, new Insets(8, 0, 0, 0));
        return label;
    }

    private static Label errorLabel() {
        var label = new Label();
        label.getStyleClass().add("error-label");
        return label;
    }
}
