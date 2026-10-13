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
            "reminder", app -> app.dashboard().notifications().show(
                    new ua.medicine.service.Reminder(1, "Марія", "Амоксицилін", "500 мг", java.time.LocalTime.of(8, 0)),
                    r -> { }, r -> { }),
            "googleSetup", app -> {
                var tabs = app.dashboard().root().lookupAll(".person-tab").toArray(new Node[0]);
                ((ButtonBase) tabs[1]).fire(); // first person's tab
                later(300, () -> clickButton(app.dashboard().stage(), "Підключити Google"));
            },
            "history", app -> clickButton(app.dashboard().stage(), "Історія"),
            "themeLight", app -> ((javafx.scene.control.MenuButton) app.dashboard().root().lookup("#themeMenu"))
                    .getItems().get(1).fire(),
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

    /** Also accepts {@code timeline:<ZOOM>[:<tab index>]}: opens that tab, the timeline and the zoom. */
    static Consumer<App> get(String name) {
        if (name.startsWith("timeline:")) {
            String[] parts = name.split(":");
            return app -> {
                var root = app.dashboard().root();
                if (parts.length > 2) {
                    ((ButtonBase) root.lookupAll(".person-tab").toArray(new Node[0])[Integer.parseInt(parts[2])]).fire();
                }
                fireToggle(root, ua.medicine.i18n.Messages.get("view.timeline"));
                fireToggle(root, ua.medicine.i18n.Messages.get("zoom." + parts[1]));
            };
        }
        return STEPS.getOrDefault(name, app -> {
        });
    }

    static void fireToggle(Node root, String text) {
        root.lookupAll(".toggle-button").stream()
                .filter(n -> n instanceof ButtonBase b && text.equals(b.getText()))
                .findFirst().ifPresent(n -> ((ButtonBase) n).fire());
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
