package ua.medicine.ui;

import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import ua.medicine.domain.DoseStatus;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;
import ua.medicine.schedule.CourseProgress;
import ua.medicine.service.DoseView;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.function.BiConsumer;

/** Dashboard card: medicine info, course progress and the current period's dose checkboxes. */
public final class MedicineCard extends VBox {

    /**
     * @param owner    shown as an avatar when not null (the "Усі" tab)
     * @param onToggle called with (doseId, newCheckedState)
     */
    public MedicineCard(Medicine medicine, Person owner, List<DoseView> doses, LocalDate today,
                        BiConsumer<Long, Boolean> onToggle, Runnable onOpen) {
        getStyleClass().add("medicine-card");
        setPrefWidth(320);

        var dot = new Circle(6, Color.web(medicine.color()));
        var name = new Label(medicine.name());
        name.getStyleClass().add("card-title");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(8, dot, name, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        if (owner != null) {
            var ownerName = new Label(owner.name());
            ownerName.getStyleClass().add("muted");
            header.getChildren().addAll(new Avatar(owner, 9), ownerName);
        }

        String details = medicine.dosage() == null || medicine.dosage().isBlank()
                ? Formats.scheduleSummary(medicine.schedule())
                : medicine.dosage() + " · " + Formats.scheduleSummary(medicine.schedule());
        var detailsLabel = new Label(details);
        detailsLabel.getStyleClass().add("muted");
        detailsLabel.setWrapText(true);

        var progress = CourseProgress.of(medicine.relevantPeriod(today), today);
        var bar = new ProgressBar(progress.map(CourseProgress::fraction).orElse(0.0));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("course-progress");
        bar.setVisible(progress.isPresent());
        bar.setManaged(progress.isPresent());
        var progressText = new Label(progress
                .map(p -> Formats.progress(p) + " · " + Messages.get("card.until", Formats.dayMonth(p.endDate())))
                .orElseGet(() -> Messages.get("card.ongoing", Formats.dayMonth(medicine.relevantPeriod(today).start()))));
        progressText.getStyleClass().add("muted");

        var chips = new FlowPane(6, 6);
        for (DoseView view : doses) {
            chips.getChildren().add(chip(medicine.schedule(), view, today, onToggle));
        }

        getChildren().addAll(header, detailsLabel, bar, progressText, chips);
        setOnMouseClicked(e -> {
            if (!(e.getTarget() instanceof CheckBox) && !isInsideCheckBox(e.getPickResult().getIntersectedNode())) {
                onOpen.run();
            }
        });
    }

    private static boolean isInsideCheckBox(javafx.scene.Node node) {
        for (var n = node; n != null; n = n.getParent()) {
            if (n instanceof CheckBox) {
                return true;
            }
        }
        return false;
    }

    private static CheckBox chip(Schedule schedule, DoseView view, LocalDate today, BiConsumer<Long, Boolean> onToggle) {
        var dose = view.dose();
        String label = chipPrefix(schedule, dose.scheduledAt().toLocalDate()) + Formats.time(dose.scheduledAt().toLocalTime());
        if (view.status() == DoseStatus.OVERDUE) {
            label += " " + Messages.get("status.overdue");
        } else if (view.status() == DoseStatus.MISSED) {
            label += " " + Messages.get("status.missed");
        }
        var box = new CheckBox(label);
        box.getStyleClass().addAll("dose-chip", "dose-" + view.status().name().toLowerCase());
        box.setSelected(view.status() == DoseStatus.TAKEN);
        // doses can be ticked and unticked only on their own day
        box.setDisable(!dose.scheduledAt().toLocalDate().equals(today));
        box.selectedProperty().addListener((obs, was, now) -> onToggle.accept(dose.id(), now));
        return box;
    }

    private static String chipPrefix(Schedule schedule, LocalDate date) {
        return switch (schedule) {
            case Schedule.Daily d -> "";
            case Schedule.Weekly w -> date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Messages.LOCALE) + " ";
            case Schedule.Monthly m -> Formats.dayMonth(date) + " ";
        };
    }
}
