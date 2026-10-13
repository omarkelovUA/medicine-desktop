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
import java.util.HashMap;
import java.util.LinkedHashSet;
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

    private final PersonRepository persons;
    private final MedicineRepository medicines;
    private final DoseRepository doses;
    private final CalendarGatewayFactory gateways;
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
                               CalendarGatewayFactory gateways) {
        this.persons = persons;
        this.medicines = medicines;
        this.doses = doses;
        this.gateways = gateways;
    }

    public void setListener(Consumer<SyncResult> listener) {
        this.listener = listener;
    }

    /** Never blocks: callers are on the UI thread while a sync may be talking to Google. */
    @Override
    public void requestSync() {
        synchronized (pendingLock) {
            if (pending != null) {
                pending.cancel(false);
            }
            pending = executor.schedule(this::runAndNotify, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
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
        int pushed = 0;
        int deleted = 0;
        int failed = 0;
        String lastError = null;
        Set<Long> authFailed = new LinkedHashSet<>();
        Map<Long, Medicine> medicineCache = new HashMap<>();

        for (Person person : persons.findAll()) {
            var needing = doses.findNeedingSync(person.id());
            if (needing.isEmpty()) {
                continue;
            }
            if (!person.googleLinked()) {
                needing.stream().filter(d -> d.syncState() == SyncState.DELETE_PENDING)
                        .forEach(d -> doses.delete(d.id()));
                continue;
            }
            try {
                CalendarGateway gateway = gateways.forPerson(person);
                String calendarId = person.googleCalendarId();
                for (DoseOccurrence dose : needing) {
                    try {
                        if (dose.syncState() == SyncState.DELETE_PENDING) {
                            deleteRemote(gateway, calendarId, dose);
                            doses.delete(dose.id());
                            deleted++;
                        } else {
                            Medicine m = medicineCache.computeIfAbsent(dose.medicineId(),
                                    id -> medicines.findById(id).orElseThrow());
                            String eventId = push(gateway, calendarId, m, dose);
                            markClean(dose, eventId);
                            pushed++;
                        }
                    } catch (UncheckedIOException e) {
                        failed++;
                        lastError = e.getMessage();
                        log.warn("Sync of dose {} failed: {}", dose.id(), e.getMessage());
                    }
                }
            } catch (AuthRequiredException e) {
                authFailed.add(person.id());
                lastError = e.getMessage();
                log.warn("Google authorization needed for person {}: {}", person.id(), e.getMessage());
            } catch (RuntimeException e) {
                failed++;
                lastError = e.getMessage();
                log.error("Sync for person {} failed", person.id(), e);
            }
        }
        return new SyncResult(pushed, deleted, failed, authFailed, lastError);
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

    /** Stores the event id; stays DIRTY if the dose changed while it was being pushed. */
    private void markClean(DoseOccurrence pushedDose, String eventId) {
        doses.recordPushed(pushedDose.id(), eventId, pushedDose.takenAt(), pushedDose.syncState());
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
