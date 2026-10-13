package ua.medicine;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.medicine.service.DayChangeDetector;
import ua.medicine.service.Reminder;
import ua.medicine.ui.DashboardController;
import ua.medicine.ui.NotificationPane;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic work every 30 s: new-day handling, reminders, dashboard refresh; sync every 15 min.
 * The day change is detected from the date on each tick rather than by a midnight timer, because
 * scheduled delays do not advance while the computer sleeps.
 */
public final class AppJobs {

    private static final Logger log = LoggerFactory.getLogger(AppJobs.class);
    private static final Duration SNOOZE = Duration.ofMinutes(10);

    private final AppContext ctx;
    private final DashboardController dashboard;
    private final NotificationPane notifications;
    private final DayChangeDetector dayChange;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "app-jobs");
        t.setDaemon(true);
        return t;
    });

    public AppJobs(AppContext ctx, DashboardController dashboard, NotificationPane notifications) {
        this.ctx = ctx;
        this.dashboard = dashboard;
        this.notifications = notifications;
        this.dayChange = new DayChangeDetector(LocalDate.now(ctx.clock()));
    }

    public void start() {
        timer.scheduleAtFixedRate(() -> Platform.runLater(this::tick), 0, 30, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(() -> ctx.sync().requestSync(), 15, 15, TimeUnit.MINUTES);
    }

    public void stop() {
        timer.shutdownNow();
    }

    private void tick() {
        try {
            if (dayChange.changed(LocalDate.now(ctx.clock()))) {
                newDay();
            }
            var due = ctx.reminderService().due();
            due.forEach(r -> notifications.show(r, this::taken, this::snooze));
            if (!due.isEmpty()) {
                attention();
            }
            dashboard.refresh();
        } catch (RuntimeException e) {
            log.error("Reminder tick failed", e);
        }
    }

    private void taken(Reminder reminder) {
        try {
            ctx.doseService().markTaken(reminder.doseId());
        } catch (RuntimeException e) {
            log.error("Marking dose {} taken failed", reminder.doseId(), e);
        }
        dashboard.refresh();
    }

    private void snooze(Reminder reminder) {
        ctx.reminderService().snooze(reminder.doseId(), SNOOZE);
    }

    private void attention() {
        if (!GraphicsEnvironment.isHeadless()) {
            Toolkit.getDefaultToolkit().beep();
        }
        var stage = dashboard.stage();
        if (stage != null && !stage.isFocused()) {
            stage.setIconified(false);
            stage.toFront();
        }
    }

    private void newDay() {
        try {
            notifications.getChildren().clear(); // yesterday's reminders can no longer be ticked
            ctx.medicineService().extendWindows();
            ctx.doseService().resolveMissed();
            ctx.sync().requestSync();
            dashboard.refresh();
        } catch (RuntimeException e) {
            log.error("New-day job failed", e);
        }
    }
}
