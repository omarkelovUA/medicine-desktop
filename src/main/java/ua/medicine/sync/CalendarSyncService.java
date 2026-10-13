package ua.medicine.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.domain.SyncState;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.service.SyncTrigger;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One-way push of doses to each linked person's Google calendar. */
public final class CalendarSyncService implements SyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);
    private static final long DEBOUNCE_MS = 2000;
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private final PersonRepository persons;
    private final MedicineRepository medicines;
    private final DoseRepository doses;
    private final CalendarGatewayFactory gateways;
    private final Clock clock;
    private final long debounceMs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "calendar-sync");
        t.setDaemon(true);
        return t;
    });
    private final Object pendingLock = new Object();
    private ScheduledFuture<?> pending;
    private volatile Consumer<SyncResult> listener = r -> {
    };

    public CalendarSyncService(PersonRepository persons, MedicineRepository medicines, DoseRepository doses,
                               CalendarGatewayFactory gateways, Clock clock) {
        this(persons, medicines, doses, gateways, clock, DEBOUNCE_MS);
    }

    CalendarSyncService(PersonRepository persons, MedicineRepository medicines, DoseRepository doses,
                        CalendarGatewayFactory gateways, Clock clock, long debounceMs) {
        this.persons = persons;
        this.medicines = medicines;
        this.doses = doses;
        this.gateways = gateways;
        this.clock = clock;
        this.debounceMs = debounceMs;
    }

    public void setListener(Consumer<SyncResult> listener) {
        this.listener = listener;
    }

    /** Never blocks: callers are on the UI thread while a sync may be talking to Google. */
    @Override
    public void requestSync() {
        synchronized (pendingLock) {
            if (executor.isShutdown()) {
                return;
            }
            if (pending != null) {
                pending.cancel(false);
            }
            pending = executor.schedule(this::runAndNotify, debounceMs, TimeUnit.MILLISECONDS);
        }
    }

    private void runAndNotify() {
        try {
            listener.accept(syncNow());
        } catch (RuntimeException e) {
            log.error("Sync failed", e);
            listener.accept(new SyncResult(0, 0, 1, Set.of(), e.getMessage()));
        }
    }

    public synchronized SyncResult syncNow() {
        var run = new Run();
        for (Person person : persons.findAll()) {
            if (!person.googleLinked()) {
                doses.findNeedingSync(person.id()).stream()
                        .filter(d -> d.syncState() == SyncState.DELETE_PENDING)
                        .forEach(d -> doses.delete(d.id()));
                continue;
            }
            try {
                CalendarGateway gateway = gateways.forPerson(person);
                try {
                    syncPerson(run, gateway, person.googleCalendarId(), person.id());
                } catch (CalendarNotFoundException e) {
                    log.warn("Calendar of person {} was deleted; creating it again", person.id());
                    String calendarId = gateway.ensureCalendar(GoogleLinkService.CALENDAR_NAME);
                    persons.save(person.withGoogle(person.googleEmail(), calendarId));
                    doses.markDirtyForPerson(person.id()); // every event went with the old calendar
                    syncPerson(run, gateway, calendarId, person.id());
                }
            } catch (AuthRequiredException e) {
                run.authFailed.add(person.id());
                run.lastError = e.getMessage();
                log.warn("Google authorization needed for person {}: {}", person.id(), e.getMessage());
            } catch (RuntimeException e) {
                run.failed++;
                run.lastError = e.getMessage();
                log.error("Sync for person {} failed", person.id(), e);
            }
        }
        return new SyncResult(run.pushed, run.deleted, run.failed, run.authFailed, run.lastError);
    }

    /** @throws CalendarNotFoundException if the person's calendar is gone */
    private void syncPerson(Run run, CalendarGateway gateway, String calendarId, long personId) {
        // doses never pushed before are sent from today on; past ones stay out of the calendar
        List<DoseOccurrence> needing = doses.findNeedingSync(personId, LocalDate.now(clock).atStartOfDay());
        for (DoseOccurrence dose : needing) {
            try {
                if (dose.syncState() == SyncState.DELETE_PENDING) {
                    deleteRemote(gateway, calendarId, dose);
                    doses.delete(dose.id());
                    run.deleted++;
                } else {
                    Medicine m = run.medicines.computeIfAbsent(dose.medicineId(),
                            id -> medicines.findById(id).orElseThrow());
                    String eventId = push(gateway, calendarId, m, dose);
                    doses.recordPushed(dose.id(), eventId, dose.takenAt(), dose.syncState());
                    run.pushed++;
                }
            } catch (UncheckedIOException e) {
                run.failed++;
                run.lastError = e.getMessage();
                log.warn("Sync of dose {} failed: {}", dose.id(), e.getMessage());
            }
        }
    }

    private static void deleteRemote(CalendarGateway gateway, String calendarId, DoseOccurrence dose) {
        if (dose.calendarEventId() == null) {
            return;
        }
        try {
            gateway.deleteEvent(calendarId, dose.calendarEventId());
        } catch (EventNotFoundException ignored) {
            // already gone
        }
    }

    private static String push(CalendarGateway gateway, String calendarId, Medicine m, DoseOccurrence dose) {
        CalendarEvent event = CalendarEvents.from(m, dose);
        if (dose.calendarEventId() != null) {
            try {
                gateway.patchEvent(calendarId, dose.calendarEventId(), event);
                return dose.calendarEventId();
            } catch (EventNotFoundException e) {
                // deleted on Google's side: create it again
            }
        }
        return gateway.insertEvent(calendarId, event);
    }

    /** Stops accepting work and waits for a push in progress, so its event id is not lost. */
    public void shutdown() {
        synchronized (pendingLock) {
            if (pending != null) {
                pending.cancel(false);
            }
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Run {
        int pushed;
        int deleted;
        int failed;
        String lastError;
        final Set<Long> authFailed = new LinkedHashSet<>();
        final Map<Long, Medicine> medicines = new HashMap<>();
    }
}
