package ua.medicine;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Map;
import java.util.function.Consumer;

/** Scripted UI steps for {@link SnapshotMain}. */
final class Steps {

    private static final Map<String, Consumer<App>> STEPS = Map.of(
            "addMedicine", app -> fire(app.dashboard().root().lookup("#addMedicineButton")),
            "addMedicineSave", app -> {
                later(600, () -> {
                    Window dialog = topWindow();
                    ((TextField) dialog.getScene().getRoot().lookup(".text-field")).setText("Тестові ліки");
                    clickButton(dialog, "Зберегти");
                });
                fire(app.dashboard().root().lookup("#addMedicineButton"));
            },
            "addMedicineInvalid", app -> {
                later(600, () -> {
                    Window dialog = topWindow();
                    ((TextField) dialog.getScene().getRoot().lookup(".text-field")).setText("");
                    clickButton(dialog, "Зберегти");
                });
                fire(app.dashboard().root().lookup("#addMedicineButton"));
            });

    private Steps() {
    }

    static Consumer<App> get(String name) {
        return STEPS.getOrDefault(name, app -> {
        });
    }

    static void later(int millis, Runnable r) {
        var p = new PauseTransition(Duration.millis(millis));
        p.setOnFinished(e -> Platform.runLater(r));
        p.play();
    }

    static void fire(Node node) {
        ((ButtonBase) node).fire();
    }

    static Window topWindow() {
        var windows = Window.getWindows();
        return windows.get(windows.size() - 1);
    }

    static void clickButton(Window window, String text) {
        window.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .findFirst()
                .ifPresent(n -> ((Button) n).fire());
    }
}
