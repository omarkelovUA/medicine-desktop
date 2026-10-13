package ua.medicine.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;
import ua.medicine.service.Reminder;

import java.util.function.Consumer;

/** Stack of reminder cards in the bottom-right corner of the main window. */
public final class NotificationPane extends VBox {

    public NotificationPane() {
        super(8);
        setAlignment(Pos.BOTTOM_RIGHT);
        setPickOnBounds(false);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setStyle("-fx-padding: 16 16 40 16;");
    }

    public void show(Reminder reminder, Consumer<Reminder> onTaken, Consumer<Reminder> onSnooze) {
        String medicine = reminder.dosage() == null || reminder.dosage().isBlank()
                ? reminder.medicineName()
                : reminder.medicineName() + " — " + reminder.dosage();
        var text = new Label(Messages.get("reminder.text", reminder.personName(), medicine,
                Formats.time(reminder.time())));
        text.setWrapText(true);
        text.setMaxWidth(320);
        text.setStyle("-fx-font-weight: bold;");
        var taken = new Button(Messages.get("reminder.taken"));
        taken.getStyleClass().add("primary");
        var snooze = new Button(Messages.get("reminder.snooze"));
        var card = new VBox(8, text, new HBox(8, taken, snooze));
        card.getStyleClass().add("notification");
        taken.setOnAction(e -> {
            getChildren().remove(card);
            onTaken.accept(reminder);
        });
        snooze.setOnAction(e -> {
            getChildren().remove(card);
            onSnooze.accept(reminder);
        });
        getChildren().add(card);
    }
}
