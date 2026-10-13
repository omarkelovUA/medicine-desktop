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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.domain.SyncState.CLEAN;
import static ua.medicine.domain.SyncState.DIRTY;
import static ua.medicine.testutil.T.d;
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
    private final CalendarGatewayFactory factory = p -> {
        var g = gateways.get(p.id());
        if (g == null) {
            throw new AuthRequiredException("no token");
        }
        return g;
    };
    private final CalendarSyncService sync = new CalendarSyncService(personRepo, medicineRepo, doseRepo, factory);

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
}
