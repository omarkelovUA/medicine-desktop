package ua.medicine.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import ua.medicine.AppContext;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;
import ua.medicine.sync.SyncResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Main window: person tabs and medicine cards. */
public final class DashboardController {

    private final AppContext ctx;
    private final Set<Long> authFailedPersonIds = new HashSet<>();
    private final NotificationPane notifications = new NotificationPane();
    private Long selectedPersonId; // null = "Усі"
    private Stage stage;

    @FXML
    private StackPane root;
    @FXML
    private HBox tabBar;
    @FXML
    private HBox personBar;
    @FXML
    private Button googleButton;
    @FXML
    private Button editPersonButton;
    @FXML
    private CheckBox showFinished;
    @FXML
    private FlowPane cards;
    @FXML
    private Label statusLabel;

    public DashboardController(AppContext ctx) {
        this.ctx = ctx;
    }

    public void start(Stage stage) {
        this.stage = stage;
        StackPane.setAlignment(notifications, Pos.BOTTOM_RIGHT);
        root.getChildren().add(notifications);
        ctx.sync().setListener(result -> Platform.runLater(() -> onSyncResult(result)));
        refresh();
    }

    public void stop() {
        ctx.sync().setListener(r -> {
        });
    }

    public StackPane root() {
        return root;
    }

    public NotificationPane notifications() {
        return notifications;
    }

    public Stage stage() {
        return stage;
    }

    @FXML
    public void refresh() {
        try {
            List<Person> persons = ctx.personService().all();
            if (selectedPersonId != null && persons.stream().noneMatch(p -> p.id().equals(selectedPersonId))) {
                selectedPersonId = null;
            }
            buildTabs(persons);
            buildPersonBar(persons);
            buildCards(persons);
        } catch (RuntimeException e) {
            Dialogs.dbError(window(), e);
        }
    }

    private void buildTabs(List<Person> persons) {
        tabBar.getChildren().clear();
        var group = new ToggleGroup();
        tabBar.getChildren().add(tab(group, new Label(Messages.get("tab.all")), null));
        for (Person p : persons) {
            var content = new HBox(6, new Avatar(p, 10), new Label(p.name()));
            content.setAlignment(Pos.CENTER_LEFT);
            int overdue = ctx.doseService().overdueCount(p.id());
            if (overdue > 0) {
                var badge = new Label(String.valueOf(overdue));
                badge.getStyleClass().add("badge");
                content.getChildren().add(badge);
            }
            tabBar.getChildren().add(tab(group, content, p.id()));
        }
        var addPerson = new Button(Messages.get("tab.addPerson"));
        addPerson.getStyleClass().add("person-tab");
        addPerson.setOnAction(e -> addPerson());
        tabBar.getChildren().add(addPerson);
    }

    private ToggleButton tab(ToggleGroup group, javafx.scene.Node content, Long personId) {
        var button = new ToggleButton();
        button.setGraphic(content);
        button.getStyleClass().add("person-tab");
        button.setToggleGroup(group);
        button.setSelected(java.util.Objects.equals(personId, selectedPersonId));
        button.setOnAction(e -> {
            selectedPersonId = personId;
            refresh();
        });
        return button;
    }

    private void buildPersonBar(List<Person> persons) {
        Optional<Person> selected = selectedPerson(persons);
        googleButton.setVisible(selected.isPresent());
        googleButton.setManaged(selected.isPresent());
        editPersonButton.setVisible(selected.isPresent());
        editPersonButton.setManaged(selected.isPresent());
        selected.ifPresent(p -> {
            boolean secretPresent = ctx.googleAuth().clientSecretPresent();
            if (p.googleLinked() && authFailedPersonIds.contains(p.id())) {
                googleButton.setText(Messages.get("google.reconnect"));
            } else if (p.googleLinked()) {
                googleButton.setText(Messages.get("google.connected", p.googleEmail()));
            } else {
                googleButton.setText(Messages.get("google.connect"));
            }
            boolean canLink = secretPresent || p.googleLinked();
            googleButton.setDisable(!canLink);
            googleButton.setTooltip(secretPresent ? null : new Tooltip(
                    Messages.get("google.noClientSecret", ctx.googleAuth().clientSecretPath().getParent())));
        });
    }

    private void buildCards(List<Person> persons) {
        cards.getChildren().clear();
        if (persons.isEmpty()) {
            cards.getChildren().add(emptyState("empty.noPersons"));
            return;
        }
        LocalDate today = LocalDate.now(ctx.clock());
        Map<Long, Person> byId = persons.stream().collect(Collectors.toMap(Person::id, Function.identity()));
        List<Medicine> medicines = selectedPersonId == null
                ? ctx.medicineRepository().findActive()
                : ctx.medicineRepository().findActiveByPerson(selectedPersonId);
        for (Medicine m : medicines) {
            if (m.isFinished(today) && !showFinished.isSelected()) {
                continue;
            }
            Person owner = selectedPersonId == null ? byId.get(m.personId()) : null;
            cards.getChildren().add(new MedicineCard(m, owner, ctx.doseService().currentPeriodDoses(m), today,
                    this::toggleDose, () -> editMedicine(m)));
        }
        if (cards.getChildren().isEmpty()) {
            cards.getChildren().add(emptyState("empty.noMedicines"));
        }
    }

    private static Label emptyState(String key) {
        var label = new Label(Messages.get(key));
        label.getStyleClass().add("empty-state");
        return label;
    }

    private void toggleDose(long doseId, boolean taken) {
        try {
            if (taken) {
                ctx.doseService().markTaken(doseId);
            } else {
                ctx.doseService().unmarkTaken(doseId);
            }
        } catch (RuntimeException e) {
            Dialogs.dbError(window(), e);
        }
        Platform.runLater(this::refresh);
    }

    private void onSyncResult(SyncResult result) {
        Set<Long> before = Set.copyOf(authFailedPersonIds);
        authFailedPersonIds.clear();
        authFailedPersonIds.addAll(result.authFailedPersonIds());
        statusLabel.setText(result.lastError() == null
                ? Messages.get("sync.ok", Formats.time(LocalTime.now(ctx.clock())))
                : Messages.get("sync.error", result.lastError()));
        if (!before.equals(authFailedPersonIds)) {
            refresh();
        }
    }

    private Optional<Person> selectedPerson(List<Person> persons) {
        return persons.stream().filter(p -> p.id().equals(selectedPersonId)).findFirst();
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    @FXML
    private void syncNow() {
        statusLabel.setText("⟳");
        ctx.sync().requestSync();
    }

    @FXML
    private void addMedicine() {
        List<Person> persons = ctx.personService().all();
        if (persons.isEmpty()) {
            Dialogs.error(window(), Messages.get("empty.noPersons"));
            return;
        }
        MedicineDialogController.show(window(), ctx, null, selectedPersonId);
        refresh();
    }

    private void editMedicine(Medicine medicine) {
        MedicineDialogController.show(window(), ctx, medicine, medicine.personId());
        refresh();
    }

    private void addPerson() {
        PersonDialogController.show(window(), ctx, null).ifPresent(p -> selectedPersonId = p.id());
        refresh();
    }

    @FXML
    private void editPerson() {
        selectedPerson(ctx.personService().all()).ifPresent(p -> PersonDialogController.show(window(), ctx, p));
        refresh();
    }

    @FXML
    private void toggleGoogle() {
        Optional<Person> selected = selectedPerson(ctx.personService().all());
        if (selected.isEmpty()) {
            return;
        }
        Person p = selected.get();
        if (p.googleLinked() && !authFailedPersonIds.contains(p.id())) {
            if (Dialogs.confirm(window(), Messages.get("confirm.disconnect", p.name()))) {
                try {
                    ctx.googleLink().unlink(p.id());
                } catch (RuntimeException e) {
                    Dialogs.dbError(window(), e);
                }
                refresh();
            }
            return;
        }
        googleButton.setDisable(true);
        googleButton.setText("…");
        ctx.io().submit(() -> {
            try {
                ctx.googleLink().link(p.id());
                Platform.runLater(() -> {
                    authFailedPersonIds.remove(p.id());
                    refresh();
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    Dialogs.error(window(), Messages.get("sync.error", e.getMessage()));
                    refresh();
                });
            }
        });
    }

    @FXML
    private void openHistory() {
        HistoryController.show(window(), ctx);
    }
}
