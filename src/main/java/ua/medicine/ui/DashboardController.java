package ua.medicine.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DashboardController.class);

    private final AppContext ctx;
    private final Theme theme;
    private final Set<Long> authFailedPersonIds = new HashSet<>();
    private final NotificationPane notifications = new NotificationPane();
    private Long selectedPersonId; // null = "Усі"
    private DashboardView view = DashboardView.CARDS;
    private TimelineView timeline;
    private Stage stage;

    @FXML
    private StackPane root;
    @FXML
    private MenuButton themeMenu;
    @FXML
    private HBox tabBar;
    @FXML
    private HBox personBar;
    @FXML
    private Button googleButton;
    @FXML
    private Button editPersonButton;
    @FXML
    private HBox viewSwitch;
    @FXML
    private CheckBox showFinished;
    @FXML
    private StackPane center;
    @FXML
    private ScrollPane cardsScroll;
    @FXML
    private FlowPane cards;
    @FXML
    private Label statusLabel;

    public DashboardController(AppContext ctx, Theme theme) {
        this.ctx = ctx;
        this.theme = theme;
    }

    public void start(Stage stage) {
        this.stage = stage;
        StackPane.setAlignment(notifications, Pos.BOTTOM_RIGHT);
        root.getChildren().add(notifications);
        buildThemeMenu();
        view = ctx.settings().dashboardView();
        timeline = new TimelineView(ctx.settings(), LocalDate.now(ctx.clock()), this::editMedicine);
        center.getChildren().add(timeline);
        buildViewSwitch();
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
            boolean timelineShown = view == DashboardView.TIMELINE && !persons.isEmpty();
            show(cardsScroll, !timelineShown);
            show(timeline, timelineShown);
            show(showFinished, view == DashboardView.CARDS);
            if (timelineShown) {
                List<Medicine> medicines = selectedPersonId == null
                        ? ctx.medicineRepository().findActive()
                        : ctx.medicineRepository().findActiveByPerson(selectedPersonId);
                timeline.show(persons, medicines, selectedPersonId == null, LocalDate.now(ctx.clock()));
            } else {
                buildCards(persons);
            }
        } catch (RuntimeException e) {
            Dialogs.dbError(window(), e);
        }
    }

    private void buildThemeMenu() {
        var group = new ToggleGroup();
        for (ThemeMode mode : ThemeMode.values()) {
            var item = new RadioMenuItem(Messages.get(mode.messageKey()));
            item.setToggleGroup(group);
            item.setSelected(mode == theme.mode());
            item.setOnAction(e -> theme.setMode(mode));
            themeMenu.getItems().add(item);
        }
    }

    private void buildViewSwitch() {
        var cardsButton = new ToggleButton(Messages.get("view.cards"));
        cardsButton.setUserData(DashboardView.CARDS);
        var timelineButton = new ToggleButton(Messages.get("view.timeline"));
        timelineButton.setUserData(DashboardView.TIMELINE);
        var group = new ToggleGroup();
        viewSwitch.getChildren().setAll(TimelineView.segmented(List.of(cardsButton, timelineButton), group));
        (view == DashboardView.CARDS ? cardsButton : timelineButton).setSelected(true);
        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now != null && now.getUserData() != view) {
                view = (DashboardView) now.getUserData();
                ctx.settings().setDashboardView(view);
                refresh();
            }
        });
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void buildTabs(List<Person> persons) {
        tabBar.getChildren().clear();
        var group = new ToggleGroup();
        tabBar.getChildren().add(tab(group, new Label(Messages.get("tab.all")), null));
        for (Person p : persons) {
            var content = new HBox(6, new Avatar(p, 10), new Label(p.name()));
            content.setAlignment(Pos.CENTER_LEFT);
            if (p.googleLinked() && authFailedPersonIds.contains(p.id())) {
                var warning = new Label("⚠");
                warning.getStyleClass().add("tab-warning");
                warning.setTooltip(new Tooltip(Messages.get("tab.authProblem")));
                content.getChildren().add(warning);
            }
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
            if (p.googleLinked() && authFailedPersonIds.contains(p.id())) {
                googleButton.setText(Messages.get("google.reconnect"));
            } else if (p.googleLinked()) {
                googleButton.setText(Messages.get("google.connected", p.googleEmail()));
            } else {
                googleButton.setText(Messages.get("google.connect"));
            }
            googleButton.setDisable(false);
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
        boolean anyoneLinked = ctx.personService().all().stream().anyMatch(Person::googleLinked);
        statusLabel.setText(SyncStatus.text(result, LocalTime.now(ctx.clock()), anyoneLinked));
        if (!before.equals(authFailedPersonIds) || result.pulled() > 0) {
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
        if (!ctx.googleAuth().clientSecretPresent() && !GoogleSetupDialog.show(window(), ctx)) {
            return; // no OAuth client yet: the setup dialog explains how to get one
        }
        googleButton.setDisable(true);
        googleButton.setText("…");
        ctx.googleAuth().cancelPendingAuthorization(); // a previous, abandoned sign-in
        ctx.io().submit(() -> {
            try {
                ctx.googleLink().link(p.id());
                Platform.runLater(() -> {
                    authFailedPersonIds.remove(p.id());
                    refresh();
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    log.warn("Linking Google for person {} failed", p.id(), e);
                    Dialogs.error(window(), Messages.get("google.linkFailed"));
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
