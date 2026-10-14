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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
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
import ua.medicine.domain.Person;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final List<PeriodEditor> periodEditors = new ArrayList<>();
    private final VBox periodsBox = new VBox(6);
    private final Button addPeriod = new Button(Messages.get("btn.addPeriod"));
    private final Label addPeriodHint = new Label(Messages.get("tooltip.endOngoingFirst"));
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
            "name", errorLabel(), "amount", errorLabel(), "periods", errorLabel(), "weekdays", errorLabel(),
            "times", errorLabel());

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

        var frequencyGroup = new ToggleGroup();
        daily.setToggleGroup(frequencyGroup);
        weekly.setToggleGroup(frequencyGroup);
        monthly.setToggleGroup(frequencyGroup);

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
            form.periods().forEach(this::addPeriodEditor);
            switch (form.frequency()) {
                case MedicineForm.WEEKLY -> weekly.setSelected(true);
                case MedicineForm.MONTHLY -> monthly.setSelected(true);
                default -> daily.setSelected(true);
            }
            form.weekdays().forEach(d -> weekdayButtons.get(d).setSelected(true));
            dayOfMonth.getValueFactory().setValue(form.dayOfMonth());
            times.addAll(form.times());
        } else {
            addPeriodEditor(MedicineForm.nextPeriod(List.of(), LocalDate.now(ctx.clock())));
            daily.setSelected(true);
            times.add(LocalTime.of(8, 0));
        }

        frequencyGroup.selectedToggleProperty().addListener((o, a, b) -> updateVisibility());
        addPeriod.setOnAction(e -> {
            addPeriodEditor(MedicineForm.nextPeriod(periodRows(), LocalDate.now(ctx.clock())));
            updatePeriodButtons();
            sizeToScene();
        });
        updatePeriodButtons();
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
        errors.keySet().forEach(field -> showError(field, problems.get(field)));
        if (!problems.isEmpty() || form.personId() == null) {
            return false;
        }
        try {
            if (existing != null) {
                savedMedicine = ctx.medicineService().update(form.toMedicine(existing.id(), existing.active()));
                return true;
            }
            Medicine fromForm = form.toMedicine(null, true);
            Optional<Medicine> sameName = ctx.medicineService().findSameName(fromForm.personId(), fromForm.name());
            if (sameName.isPresent() && Dialogs.confirm(name.getScene().getWindow(),
                    Messages.get("confirm.addPeriodToExisting", sameName.get().name()))) {
                try {
                    savedMedicine = ctx.medicineService().appendPeriods(sameName.get().id(), fromForm);
                } catch (IllegalArgumentException overlap) {
                    showError("periods", "error.periodsOverlap");
                    return false;
                }
            } else {
                savedMedicine = ctx.medicineService().create(fromForm);
            }
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
                periodRows(), frequency, days, dayOfMonth.getValue(), List.copyOf(times));
    }

    private Node content() {
        addPeriodHint.getStyleClass().add("muted");
        var addPeriodRow = new HBox(8, addPeriod, addPeriodHint);
        addPeriodRow.setAlignment(Pos.CENTER_LEFT);
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
                formLabel("field.periods"), periodsBox, addPeriodRow, errors.get("amount"), errors.get("periods"),
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

    private void addPeriodEditor(MedicineForm.PeriodRow row) {
        var editor = new PeriodEditor(row, this::updatePeriodButtons);
        editor.remove.setOnAction(e -> {
            periodEditors.remove(editor);
            periodsBox.getChildren().remove(editor);
            updatePeriodButtons();
            sizeToScene();
        });
        editor.endToday.setOnAction(e -> {
            editor.set(MedicineForm.endToday(editor.row(), LocalDate.now(ctx.clock())));
            updatePeriodButtons();
        });
        periodEditors.add(editor);
        periodsBox.getChildren().add(editor);
    }

    /** Rows of the periods section; a cleared start date counts as today. */
    private List<MedicineForm.PeriodRow> periodRows() {
        LocalDate today = LocalDate.now(ctx.clock());
        return periodEditors.stream().map(PeriodEditor::row)
                .map(r -> r.start() != null ? r : new MedicineForm.PeriodRow(today, r.ongoing(), r.amount(), r.unit()))
                .toList();
    }

    private void updatePeriodButtons() {
        LocalDate today = LocalDate.now(ctx.clock());
        List<MedicineForm.PeriodRow> rows = periodRows();
        for (int i = 0; i < periodEditors.size(); i++) {
            PeriodEditor editor = periodEditors.get(i);
            editor.remove.setDisable(periodEditors.size() == 1);
            boolean canEnd = rows.get(i).ongoing() && !rows.get(i).start().isAfter(today);
            editor.endToday.setVisible(canEnd);
            editor.endToday.setManaged(canEnd);
        }
        boolean lastOngoing = rows.stream().max(Comparator.comparing(MedicineForm.PeriodRow::start))
                .map(MedicineForm.PeriodRow::ongoing).orElse(false);
        addPeriod.setDisable(lastOngoing);
        // a disabled button shows no tooltip, so the hint is a label beside it
        addPeriodHint.setVisible(lastOngoing);
        addPeriodHint.setManaged(lastOngoing);
    }

    private void showError(String field, String key) {
        Label label = errors.get(field);
        label.setText(key == null ? "" : Messages.get(key));
        label.setVisible(key != null);
        label.setManaged(key != null);
        sizeToScene();
    }

    private void sizeToScene() {
        if (name.getScene() != null) {
            name.getScene().getWindow().sizeToScene();
        }
    }

    private void updateVisibility() {
        weekdaysRow.setVisible(weekly.isSelected());
        weekdaysRow.setManaged(weekly.isSelected());
        dayOfMonthRow.setVisible(monthly.isSelected());
        dayOfMonthRow.setManaged(monthly.isSelected());
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
