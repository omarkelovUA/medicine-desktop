package ua.medicine.sync;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.persistence.Database;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.service.DoseService;
import ua.medicine.service.MedicineService;
import ua.medicine.service.SyncTrigger;
import ua.medicine.testutil.MutableClock;
import ua.medicine.testutil.TestDb;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.domain.SyncState.CLEAN;
import static ua.medicine.domain.SyncState.DIRTY;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class CalendarSyncServiceTest {

    private final Database db = TestDb.create();
    private final PersonRepository personRepo = new PersonRepository(db);
    private final MedicineRepository medicineRepo = new MedicineRepository(db);
    private final DoseRepository doseRepo = new DoseRepository(db);
    private final MutableClock clock = MutableClock.at("2026-10-13T12:00");
    private final MedicineService medicines = new MedicineService(medicineRepo, doseRepo, clock, SyncTrigger.NOOP);
    private final DoseService doseService = new DoseService(doseRepo, medicineRepo, clock, SyncTrigger.NOOP);
    private final Map<Long, FakeCalendarGateway> gateways = new HashMap<>();
    private final Map<Long, Integer> gatewayRequests = new HashMap<>();
    private final CalendarGatewayFactory factory = p -> {
        gatewayRequests.merge(p.tokenOwnerId(), 1, Integer::sum);
        var g = gateways.get(p.tokenOwnerId());
        if (g == null) {
            throw new AuthRequiredException("no token");
        }
        return g;
    };
    private final CalendarSyncService sync = new CalendarSyncService(personRepo, medicineRepo, doseRepo, factory, clock);

    private final Person oleksandr = personRepo.save(new Person(null, "Олександр", "#3a7bd5", "o@gmail.com", "calO"));
    private final Person maria = personRepo.save(new Person(null, "Марія", "#c0397b", "m@gmail.com", "calM"));
    private final FakeCalendarGateway fake = new FakeCalendarGateway();

    // 2 doses each: today 08:00 and 20:00
    private final Medicine amox = medicines.create(med(oleksandr.id(), d("2026-10-13"), new Course.Fixed(1, DAYS),
            new Schedule.Daily(List.of(t("08:00"), t("20:00")))));

    {
        gateways.put(oleksandr.id(), fake);
    }

    private List<DoseOccurrence> doses(Medicine m) {
        return doseRepo.findByMedicineBetween(m.id(), d("2026-10-01").atStartOfDay(), d("2026-12-01").atStartOfDay());
    }

    @Test
    void insertsDirtyAndMarksClean() {
        var r = sync.syncNow();
        assertThat(r.pushed()).isEqualTo(2);
        assertThat(fake.events).hasSize(2);
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).isEmpty();
        assertThat(doses(amox)).allSatisfy(dose -> {
            assertThat(dose.syncState()).isEqualTo(CLEAN);
            assertThat(dose.calendarEventId()).isNotNull();
        });
    }

    @Test
    void takenDosePatchedNotReinserted() {
        sync.syncNow();
        var first = doses(amox).getFirst();
        doseService.markTaken(first.id());
        sync.syncNow();
        assertThat(fake.inserts).isEqualTo(2);
        assertThat(fake.events.get(first.calendarEventId()).taken()).isTrue();
        assertThat(fake.events.get(first.calendarEventId()).summary()).startsWith("✅");
    }

    @Test
    void patch404Reinserts() {
        sync.syncNow();
        var first = doses(amox).getFirst();
        fake.events.remove(first.calendarEventId());
        doseRepo.update(first.withSyncState(DIRTY));
        sync.syncNow();
        var after = doseRepo.findById(first.id()).orElseThrow();
        assertThat(after.calendarEventId()).isNotEqualTo(first.calendarEventId());
        assertThat(fake.events).containsKey(after.calendarEventId());
        assertThat(after.syncState()).isEqualTo(CLEAN);
    }

    @Test
    void deletePendingDeletesEventAndRow() {
        sync.syncNow();
        medicines.delete(amox.id()); // 20:00 is in the future → DELETE_PENDING
        var r = sync.syncNow();
        assertThat(r.deleted()).isEqualTo(1);
        assertThat(fake.events).hasSize(1);
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).isEmpty();
    }

    @Test
    void deletePendingWithEventAlreadyGoneIsStillRemoved() {
        sync.syncNow();
        fake.events.clear();
        medicines.delete(amox.id());
        sync.syncNow();
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).isEmpty();
    }

    @Test
    void unlinkedPersonSkippedButDeletePendingRowsRemoved() {
        var sofia = personRepo.save(new Person(null, "Софія", "#e08a1e", null, null));
        var vit = medicines.create(med(sofia.id(), d("2026-10-13"), new Course.Fixed(1, DAYS),
                new Schedule.Daily(List.of(t("20:00")))));
        var dose = doses(vit).getFirst();
        doseRepo.update(dose.withCalendarEventId("old").withSyncState(ua.medicine.domain.SyncState.DELETE_PENDING));
        sync.syncNow();
        assertThat(doseRepo.findById(dose.id())).isEmpty();
        assertThat(fake.inserts).isEqualTo(2); // only Олександр's
    }

    @Test
    void ioFailureKeepsDirty() {
        fake.failIo = true;
        var r = sync.syncNow();
        assertThat(r.failed()).isPositive();
        assertThat(r.lastError()).isNotBlank();
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).hasSize(2);
    }

    @Test
    void authFailureForOnePersonDoesNotStopOthers() {
        var mariaGateway = new FakeCalendarGateway();
        mariaGateway.failAuth = true;
        gateways.put(maria.id(), mariaGateway);
        medicines.create(med(maria.id(), d("2026-10-13"), new Course.Fixed(1, DAYS),
                new Schedule.Daily(List.of(t("20:00")))));
        var r = sync.syncNow();
        assertThat(r.authFailedPersonIds()).containsExactly(maria.id());
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).isEmpty();
        assertThat(doseRepo.findNeedingSync(maria.id())).hasSize(1);
    }

    @Test
    void missingCredentialIsAuthFailure() {
        medicines.create(med(maria.id(), d("2026-10-13"), new Course.Fixed(1, DAYS),
                new Schedule.Daily(List.of(t("20:00")))));
        assertThat(sync.syncNow().authFailedPersonIds()).containsExactly(maria.id());
    }

    @Test
    void requestSyncDoesNotBlockWhileSyncIsRunning() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fake.onInsert = () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var running = new Thread(sync::syncNow);
        running.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertTimeoutPreemptively(Duration.ofMillis(500), sync::requestSync);
        } finally {
            release.countDown();
            running.join(5000);
            sync.shutdown();
        }
    }

    @Test
    void unexpectedErrorForOnePersonDoesNotStopOthers() {
        fake.failUnexpected = true; // Олександр (synced first) breaks
        var mariaGateway = new FakeCalendarGateway();
        gateways.put(maria.id(), mariaGateway);
        medicines.create(med(maria.id(), d("2026-10-13"), new Course.Fixed(1, DAYS),
                new Schedule.Daily(List.of(t("20:00")))));
        var r = sync.syncNow();
        assertThat(r.failed()).isPositive();
        assertThat(mariaGateway.events).hasSize(1);
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).hasSize(2);
    }

    @Test
    void deletedCalendarIsRecreatedAndAllDosesPushedAgain() {
        sync.syncNow();
        fake.calendarDeleted = true;
        fake.events.clear();
        doseService.markTaken(doses(amox).getFirst().id());
        var r = sync.syncNow();
        assertThat(r.failed()).isZero();
        assertThat(fake.calendarsCreated).isEqualTo(1);
        assertThat(fake.events).hasSize(2);
        assertThat(personRepo.findById(oleksandr.id()).orElseThrow().googleCalendarId()).isEqualTo("cal-Ліки");
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).isEmpty();
    }

    @Test
    void pastDosesNeverPushedAreNotSentOnFirstLink() {
        doseRepo.insertAll(List.of(DoseOccurrence.planned(amox.id(), clock.instant()
                .atZone(clock.getZone()).toLocalDate().minusDays(1).atTime(8, 0))));
        assertThat(sync.syncNow().pushed()).isEqualTo(2); // only today's 08:00 and 20:00
        assertThat(fake.inserts).isEqualTo(2);
    }

    @Test
    void shutdownWaitsForRunningPush() throws Exception {
        var quick = new CalendarSyncService(personRepo, medicineRepo, doseRepo, factory, clock, 0);
        var entered = new CountDownLatch(1);
        fake.onInsert = () -> {
            entered.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        quick.requestSync();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        quick.shutdown();
        assertThat(doseRepo.findNeedingSync(oleksandr.id())).isEmpty();
    }

    private static Instant at(String isoLocal) {
        return dt(isoLocal).atZone(ZoneId.systemDefault()).toInstant();
    }

    @Test
    void phoneTickTakesDoseAndPushesItBack() {
        sync.syncNow();
        var morning = doses(amox).getFirst();
        fake.phoneEdit(morning.calendarEventId(), "10", at("2026-10-13T09:10"));

        var r = sync.syncNow();

        assertThat(r.pulled()).isEqualTo(1);
        var after = doseRepo.findById(morning.id()).orElseThrow();
        assertThat(after.takenAt()).isEqualTo(dt("2026-10-13T09:10"));
        assertThat(after.syncState()).isEqualTo(CLEAN);
        assertThat(fake.events.get(morning.calendarEventId()).summary()).startsWith("✅");
    }

    @Test
    void sageTickBecomesBasil() {
        sync.syncNow();
        var morning = doses(amox).getFirst();
        fake.phoneEdit(morning.calendarEventId(), "2", at("2026-10-13T09:10"));

        sync.syncNow();

        assertThat(doseRepo.findById(morning.id()).orElseThrow().takenAt()).isNotNull();
        assertThat(fake.colors.get(morning.calendarEventId())).isEqualTo("10");
    }

    @Test
    void phoneUntickUntakesDose() {
        var morning = doses(amox).getFirst();
        doseService.markTaken(morning.id());
        sync.syncNow();
        String eventId = doseRepo.findById(morning.id()).orElseThrow().calendarEventId();
        fake.phoneEdit(eventId, null, at("2026-10-13T12:30"));

        var r = sync.syncNow();

        assertThat(r.pulled()).isEqualTo(1);
        var after = doseRepo.findById(morning.id()).orElseThrow();
        assertThat(after.takenAt()).isNull();
        assertThat(after.missed()).isFalse();
        assertThat(fake.events.get(eventId).summary()).startsWith("💊");
    }

    @Test
    void phoneTickSeenNextDayClearsMissed() {
        sync.syncNow();
        var morning = doses(amox).getFirst();
        fake.phoneEdit(morning.calendarEventId(), "10", at("2026-10-13T09:10"));
        clock.set(dt("2026-10-14T08:00"));
        doseService.resolveMissed();
        assertThat(doseRepo.findById(morning.id()).orElseThrow().missed()).isTrue();

        sync.syncNow();

        var after = doseRepo.findById(morning.id()).orElseThrow();
        assertThat(after.takenAt()).isEqualTo(dt("2026-10-13T09:10"));
        assertThat(after.missed()).isFalse();
    }

    @Test
    void phoneTickOnLaterDayIsRestored() {
        sync.syncNow();
        var morning = doses(amox).getFirst();
        fake.phoneEdit(morning.calendarEventId(), "10", at("2026-10-14T07:00"));
        clock.set(dt("2026-10-14T08:00"));

        var r = sync.syncNow();

        assertThat(r.pulled()).isZero();
        assertThat(doseRepo.findById(morning.id()).orElseThrow().takenAt()).isNull();
        assertThat(fake.colors.get(morning.calendarEventId())).isNull();
    }

    @Test
    void appTickBeforePullWins() {
        sync.syncNow();
        var evening = doses(amox).getLast();
        doseService.markTaken(evening.id());
        fake.phoneEdit(evening.calendarEventId(), "5", at("2026-10-13T12:01"));

        sync.syncNow();

        assertThat(doseRepo.findById(evening.id()).orElseThrow().takenAt()).isNotNull();
        assertThat(fake.colors.get(evening.calendarEventId())).isEqualTo("10");
    }

    @Test
    void listFailureCountsAsFailedAndChangesNothing() {
        sync.syncNow();
        var morning = doses(amox).getFirst();
        fake.phoneEdit(morning.calendarEventId(), "10", at("2026-10-13T09:10"));
        fake.failList = true;

        var r = sync.syncNow();

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.lastError()).isNotBlank();
        assertThat(doseRepo.findById(morning.id()).orElseThrow().takenAt()).isNull();
    }

    private Person child(String name, Person host) {
        return personRepo.save(new Person(null, name, "#e08a1e", null, null)
                .withHostedGoogle(host.googleEmail(), "cal-Ліки " + name, host.id(), "Ліки " + name));
    }

    private Medicine eveningMedicine(Person p) {
        return medicines.create(med(p.id(), d("2026-10-13"), new Course.Fixed(1, DAYS),
                new Schedule.Daily(List.of(t("20:00")))));
    }

    @Test
    void childDosesGoToChildCalendarThroughParentGateway() {
        var sofia = child("Софія", oleksandr);
        var vit = eveningMedicine(sofia);

        sync.syncNow();

        var dose = doses(vit).getFirst();
        assertThat(dose.syncState()).isEqualTo(CLEAN);
        assertThat(fake.eventCalendar.get(dose.calendarEventId())).isEqualTo("cal-Ліки Софія");
        assertThat(fake.events).hasSize(3);
        assertThat(gatewayRequests.get(oleksandr.id())).isEqualTo(1);
    }

    @Test
    void phoneMarkOnChildEventIsPulled() {
        var sofia = child("Софія", oleksandr);
        var vit = eveningMedicine(sofia);
        sync.syncNow();
        var dose = doses(vit).getFirst();
        fake.phoneEdit(dose.calendarEventId(), "10", at("2026-10-13T11:30"));

        var r = sync.syncNow();

        assertThat(r.pulled()).isEqualTo(1);
        assertThat(doseRepo.findById(dose.id()).orElseThrow().takenAt()).isEqualTo(dt("2026-10-13T11:30"));
    }

    @Test
    void parentAuthFailureMarksChildrenToo() {
        var sofia = child("Софія", oleksandr);
        fake.failAuth = true;
        assertThat(sync.syncNow().authFailedPersonIds()).contains(oleksandr.id(), sofia.id());
    }

    @Test
    void childBeforeParentInListStillSyncsAndSharesAuthFailure() {
        var placeholder = new Person(null, "Іван", "#222222", "i@gmail.com", "calI");
        // the child is saved first, so it has the smaller id and is synced before its parent
        var sofia = personRepo.save(new Person(null, "Софія", "#e08a1e", null, null));
        var ivan = personRepo.save(placeholder);
        sofia = personRepo.save(sofia.withHostedGoogle("i@gmail.com", "cal-Ліки Софія", ivan.id(), "Ліки Софія"));
        var ivanGateway = new FakeCalendarGateway();
        gateways.put(ivan.id(), ivanGateway);
        var vit = eveningMedicine(sofia);
        eveningMedicine(ivan);

        sync.syncNow();

        assertThat(ivanGateway.events).hasSize(2);
        assertThat(doses(vit).getFirst().syncState()).isEqualTo(CLEAN);
        assertThat(gatewayRequests.get(ivan.id())).isEqualTo(1);

        ivanGateway.failAuth = true;
        assertThat(sync.syncNow().authFailedPersonIds()).contains(sofia.id(), ivan.id());
    }

    @Test
    void deletedChildCalendarIsRecreatedWithChildNameAndKeepsHost() {
        var sofia = child("Софія", oleksandr);
        var vit = eveningMedicine(sofia);
        sync.syncNow();
        // only the child's calendar is gone: the fake's flag is per account, so take the parent out of the sync
        personRepo.save(oleksandr.withGoogle(null, null));
        fake.calendarDeleted = true;
        fake.events.clear();

        sync.syncNow();

        var after = personRepo.findById(sofia.id()).orElseThrow();
        assertThat(fake.created).containsExactly("Ліки Софія");
        assertThat(after.googleCalendarId()).startsWith("new-");
        assertThat(after.hosted()).isTrue();
        assertThat(after.calendarName()).isEqualTo("Ліки Софія");
        assertThat(doses(vit).getFirst().syncState()).isEqualTo(CLEAN);
        assertThat(fake.eventCalendar.get(doses(vit).getFirst().calendarEventId())).isEqualTo(after.googleCalendarId());
    }

    @Test
    void renamedChildCalendarIsRenamedOnce() {
        var sofia = child("Софія", oleksandr);
        personRepo.save(sofia.withNameAndColor("Соня", sofia.color()));

        sync.syncNow();
        sync.syncNow();

        assertThat(fake.renamed).containsEntry("cal-Ліки Софія", "Ліки Соня");
        assertThat(fake.renames).isEqualTo(1);
        assertThat(personRepo.findById(sofia.id()).orElseThrow().calendarName()).isEqualTo("Ліки Соня");
    }

    @Test
    void renameFailureIsRetriedNextSync() {
        var sofia = child("Софія", oleksandr);
        var vit = eveningMedicine(sofia);
        personRepo.save(personRepo.findById(sofia.id()).orElseThrow().withNameAndColor("Соня", sofia.color()));
        fake.failRename = true;

        var r = sync.syncNow();

        assertThat(r.failed()).isPositive();
        assertThat(personRepo.findById(sofia.id()).orElseThrow().calendarName()).isEqualTo("Ліки Софія");
        assertThat(doses(vit).getFirst().syncState()).isEqualTo(CLEAN);

        fake.failRename = false;
        sync.syncNow();
        assertThat(personRepo.findById(sofia.id()).orElseThrow().calendarName()).isEqualTo("Ліки Соня");
    }

    @Test
    void unlinkDuringRenameIsNotUndone() {
        var sofia = child("Софія", oleksandr);
        personRepo.save(sofia.withNameAndColor("Соня", "#000000"));
        fake.onCalendarCall = () -> personRepo.save(
                personRepo.findById(sofia.id()).orElseThrow().withNameAndColor("Соня", "#ffffff").withGoogle(null, null));

        sync.syncNow();

        var after = personRepo.findById(sofia.id()).orElseThrow();
        assertThat(after.googleLinked()).isFalse();
        assertThat(after.hosted()).isFalse();
        assertThat(after.color()).isEqualTo("#ffffff");
    }

    @Test
    void unlinkDuringCalendarRecoveryIsNotUndone() {
        var sofia = child("Софія", oleksandr);
        eveningMedicine(sofia);
        sync.syncNow();
        personRepo.save(oleksandr.withGoogle(null, null));
        fake.calendarDeleted = true;
        fake.onCalendarCall = () -> personRepo.save(personRepo.findById(sofia.id()).orElseThrow().withGoogle(null, null));

        sync.syncNow();

        var after = personRepo.findById(sofia.id()).orElseThrow();
        assertThat(after.googleLinked()).isFalse();
        assertThat(after.hosted()).isFalse();
    }

    @Test
    void deletedChildCalendarIsNotReplacedByAnotherWithTheSameName() {
        var sofia = child("Софія", oleksandr);
        personRepo.save(oleksandr.withGoogle(null, null)); // only the child syncs
        fake.owned.add("cal-Ліки Оля"); // left by an earlier Оля
        personRepo.save(sofia.withNameAndColor("Оля", sofia.color()));
        fake.calendarDeleted = true;

        sync.syncNow();

        var after = personRepo.findById(sofia.id()).orElseThrow();
        assertThat(after.googleCalendarId()).isNotEqualTo("cal-Ліки Оля");
        assertThat(fake.created).containsExactly("Ліки Оля");
        assertThat(after.calendarName()).isEqualTo("Ліки Оля");
    }
}
