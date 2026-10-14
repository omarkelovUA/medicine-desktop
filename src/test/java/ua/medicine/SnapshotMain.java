package ua.medicine;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Dev tool (test scope): starts the app on a scratch data dir, optionally seeds demo data,
 * runs a scripted step and writes PNG snapshots of all open windows.
 * Args: dataDir outPrefix [seed] [step]
 */
public final class SnapshotMain {

    static String outPrefix;
    static String step = "";

    public static void main(String[] args) throws Exception {
        System.setProperty("medicine.home", args[0]);
        outPrefix = args[1];
        if (args.length > 2 && args[2].equals("seed")) {
            seed();
        }
        if (args.length > 3) {
            step = args[3];
        }
        Application.launch(Snapshotting.class);
    }

    private static void seed() {
        try (var ctx = new AppContext(AppPaths.defaults())) {
            var today = LocalDate.now();
            var o = ctx.personService().create("Олександр", "#3a7bd5");
            var m = ctx.personService().create("Марія", "#c0397b");
            ctx.personService().create("Софія", "#e08a1e");
            var t = (java.util.function.Function<String, LocalTime>) LocalTime::parse;
            var amox = ctx.medicineService().create(new Medicine(null, o.id(), "Амоксицилін", "500 мг, після їжі",
                    "#d5a23a", today.minusDays(3), new Course.Fixed(10, PeriodUnit.DAYS),
                    new Schedule.Daily(List.of(t.apply("08:00"), t.apply("14:00"), t.apply("20:00"))), true));
            ctx.medicineService().create(new Medicine(null, o.id(), "Вітамін D", "2000 МО", "#3a7bd5",
                    today.minusDays(40), new Course.Ongoing(), new Schedule.Daily(List.of(t.apply("08:00"))), true));
            ctx.medicineService().create(new Medicine(null, m.id(), "Омепразол", "20 мг, натще", "#9b59b6",
                    today.minusDays(15), new Course.Fixed(4, PeriodUnit.WEEKS),
                    new Schedule.Daily(List.of(t.apply("00:01"))), true));
            ctx.medicineService().create(new Medicine(null, m.id(), "Магній B6", "1 таблетка", "#2f7d5b",
                    today.minusDays(20), new Course.Fixed(3, PeriodUnit.MONTHS),
                    new Schedule.Weekly(Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), List.of(t.apply("09:00"))), true));
            // taken again after a rest: two periods on one line
            ctx.medicineService().create(new Medicine(null, o.id(), "Вітамін A", "1 капсула", "#e08a1e", List.of(
                    new Period(today.minusDays(200), new Course.Fixed(30, PeriodUnit.DAYS)),
                    new Period(today.minusDays(20), new Course.Fixed(2, PeriodUnit.MONTHS))),
                    new Schedule.Daily(List.of(t.apply("09:00"))), true));
            // in a rest now, next period planned
            ctx.medicineService().create(new Medicine(null, m.id(), "Залізо", "1 таблетка", "#c0392b", List.of(
                    new Period(today.minusDays(60), new Course.Fixed(10, PeriodUnit.DAYS)),
                    new Period(today.plusDays(10), new Course.Fixed(10, PeriodUnit.DAYS))),
                    new Schedule.Daily(List.of(t.apply("13:00"))), true));
            var first = ctx.doseRepository().findByMedicineBetween(amox.id(), today.atStartOfDay(),
                    today.plusDays(1).atStartOfDay()).getFirst();
            ctx.doseService().markTaken(first.id());
            // some history: a taken and a missed dose yesterday
            ctx.doseRepository().insertAll(List.of(
                    ua.medicine.domain.DoseOccurrence.planned(amox.id(), today.minusDays(1).atTime(8, 0))
                            .withTakenAt(today.minusDays(1).atTime(8, 10)),
                    ua.medicine.domain.DoseOccurrence.planned(amox.id(), today.minusDays(1).atTime(20, 0))));
            ctx.doseService().resolveMissed();
            System.out.println("seeded at " + LocalDateTime.now());
        }
    }

    public static final class Snapshotting extends App {
        @Override
        public void start(Stage stage) throws IOException {
            super.start(stage);
            Consumer<App> action = Steps.get(step);
            var first = new PauseTransition(Duration.millis(800));
            first.setOnFinished(e -> {
                Platform.runLater(() -> action.accept(this));
                var second = new PauseTransition(Duration.millis(1200));
                second.setOnFinished(e2 -> {
                    int i = 0;
                    for (Window w : List.copyOf(Window.getWindows())) {
                        write(w.getScene(), outPrefix + "-" + (i++) + ".png");
                    }
                    Runtime.getRuntime().halt(0); // dev tool: skip shutdown while dialogs are open
                });
                second.play();
            });
            first.play();
        }
    }

    static void write(Scene scene, String path) {
        WritableImage img = scene.snapshot(null);
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(out, "png", new File(path));
            System.out.println("snapshot " + path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Clicks a node found by CSS lookup on the dashboard. */
    static void fire(App app, String selector) {
        Node n = app.dashboard().root().lookup(selector);
        if (n instanceof javafx.scene.control.ButtonBase b) {
            b.fire();
        }
    }
}
