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
import java.time.LocalDateTime;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pushes doses to each linked person's Google calendar and reads back the marks made there on the phone. */
public final class CalendarSyncService implements SyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);
    private static final long DEBOUNCE_MS = 2000;
    private static final long SHUTDOWN_WAIT_SECONDS = 10;
    private static final int PULL_DAYS = 7;

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
            listener.accept(new SyncResult(0, 0, 0, 1, Set.of(), e.getMessage()));
        }
    }

    public synchronized SyncResult syncNow() {
        var run = new Run();
        List<Person> all = persons.findAll();
        for (Person person : all) {
            if (!person.googleLinked()) {
                doses.findNeedingSync(person.id()).stream()
                        .filter(d -> d.syncState() == SyncState.DELETE_PENDING)
                        .forEach(d -> doses.delete(d.id()));
                continue;
            }
            long owner = person.tokenOwnerId();
            if (run.failedOwners.contains(owner)) {
                continue;
            }
            try {
                // a parent and their children share one account, so one gateway
                CalendarGateway gateway = run.gateways.computeIfAbsent(owner, k -> gateways.forPerson(person));
                try {
                    syncPerson(run, gateway, person);
                } catch (CalendarNotFoundException e) {
                    log.warn("Calendar of person {} was deleted; creating it again", person.id());
                    String name = GoogleLinkService.calendarNameFor(person);
                    Person recreated = person.withCalendar(gateway.ensureCalendar(name), person.hosted() ? name : null);
                    if (persons.updateCalendar(person.id(), person.googleCalendarId(), recreated.googleCalendarId(),
                            recreated.calendarName())) {
                        doses.markDirtyForPerson(person.id()); // every event went with the old calendar
                        syncPerson(run, gateway, recreated);
                    }
                }
            } catch (AuthRequiredException e) {
                run.failedOwners.add(owner);
                run.lastError = e.getMessage();
                log.warn("Google authorization needed for person {}: {}", person.id(), e.getMessage());
            } catch (RuntimeException e) {
                run.failed++;
                run.lastError = e.getMessage();
                log.error("Sync for person {} failed", person.id(), e);
            }
        }
        // a lapsed token fails everyone reached with it, whichever of them was synced first
        Set<Long> authFailed = all.stream()
                .filter(p -> p.googleLinked() && run.failedOwners.contains(p.tokenOwnerId()))
                .map(Person::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new SyncResult(run.pushed, run.pulled, run.deleted, run.failed, authFailed, run.lastError);
    }

    /** @throws CalendarNotFoundException if the person's calendar is gone */
    private void syncPerson(Run run, CalendarGateway gateway, Person person) {
        String calendarId = person.googleCalendarId();
        long personId = person.id();
        renameIfNeeded(run, gateway, person);
        pushChanges(run, gateway, calendarId, personId);
        if (pull(run, gateway, calendarId, personId)) {
            pushChanges(run, gateway, calendarId, personId); // the changed events take the app's form
        }
    }

    /**
     * A child's calendar follows the child's name. Offline, the rename waits for the next sync.
     *
     * @throws CalendarNotFoundException if the person's calendar is gone
     */
    private void renameIfNeeded(Run run, CalendarGateway gateway, Person person) {
        String name = GoogleLinkService.calendarNameFor(person);
        if (!person.hosted() || name.equals(person.calendarName())) {
            return;
        }
        try {
            gateway.renameCalendar(person.googleCalendarId(), name);
            persons.updateCalendar(person.id(), person.googleCalendarId(), person.googleCalendarId(), name);
        } catch (UncheckedIOException e) {
            run.failed++;
            run.lastError = e.getMessage();
            log.warn("Renaming the calendar of person {} failed: {}", person.id(), e.getMessage());
        }
    }

    private void pushChanges(Run run, CalendarGateway gateway, String calendarId, long personId) {
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

    /**
     * Applies event colours changed on the phone to the person's recent doses.
     *
     * @return whether any dose became DIRTY and so needs pushing
     * @throws CalendarNotFoundException if the person's calendar is gone
     */
    private boolean pull(Run run, CalendarGateway gateway, String calendarId, long personId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = today.minusDays(PULL_DAYS).atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        List<RemoteEvent> events;
        try {
            events = gateway.listEvents(calendarId, from, to);
        } catch (UncheckedIOException e) {
            run.failed++;
            run.lastError = e.getMessage();
            log.warn("Reading events of person {} failed: {}", personId, e.getMessage());
            return false;
        }
        Map<String, DoseOccurrence> byEvent = doses.findWithEventForPerson(personId, from, to).stream()
                .collect(Collectors.toMap(DoseOccurrence::calendarEventId, Function.identity(), (a, b) -> a));
        boolean changed = false;
        for (RemoteEvent event : events) {
            DoseOccurrence dose = byEvent.get(event.id());
            if (dose == null) {
                continue;
            }
            switch (RemoteMarks.decide(dose, event, clock.getZone())) {
                case RemoteMarks.Take take -> {
                    if (doses.markTakenFromPhone(dose.id(), take.at())) {
                        run.pulled++;
                        changed = true;
                    }
                }
                case RemoteMarks.Untake ignored -> {
                    boolean dayOver = dose.scheduledAt().toLocalDate().isBefore(today);
                    if (doses.unmarkTakenFromPhone(dose.id(), dayOver)) {
                        run.pulled++;
                        changed = true;
                    }
                }
                case RemoteMarks.Restore ignored -> changed |= doses.markDirtyIfClean(dose.id());
                case RemoteMarks.None ignored -> {
                }
            }
        }
        return changed;
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
        int pulled;
        int deleted;
        int failed;
        String lastError;
        final Set<Long> failedOwners = new LinkedHashSet<>();
        final Map<Long, CalendarGateway> gateways = new HashMap<>();
        final Map<Long, Medicine> medicines = new HashMap<>();
    }
}
