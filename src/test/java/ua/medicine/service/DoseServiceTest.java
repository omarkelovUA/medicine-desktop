package ua.medicine.service;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.persistence.Database;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.testutil.MutableClock;
import ua.medicine.testutil.TestDb;

import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.THURSDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.medicine.domain.DoseStatus.PENDING;
import static ua.medicine.domain.SyncState.CLEAN;
import static ua.medicine.domain.SyncState.DIRTY;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class DoseServiceTest {

    private final Database db = TestDb.create();
    private final DoseRepository doseRepo = new DoseRepository(db);
    private final MedicineRepository medicineRepo = new MedicineRepository(db);
    private final MutableClock clock = MutableClock.at("2026-10-13T12:00");
    private final MedicineService medicines = new MedicineService(medicineRepo, doseRepo, clock, SyncTrigger.NOOP);
    private final DoseService svc = new DoseService(doseRepo, medicineRepo, clock, SyncTrigger.NOOP);
    private final long pid = new PersonRepository(db).save(new Person(null, "Марія", "#c0397b", null, null)).id();
    private final Medicine m = medicines.create(med(pid, d("2026-10-13"), new Course.Ongoing(),
            new Schedule.Daily(List.of(t("08:00"), t("20:00")))));
    private final long id08 = idAt("2026-10-13T08:00");
    private final long id20 = idAt("2026-10-13T20:00");

    private long idAt(String at) {
        return doseRepo.findByMedicineBetween(m.id(), dt(at), dt(at).plusMinutes(1)).getFirst().id();
    }

    @Test
    void markTakenSetsTimeAndDirty() {
        doseRepo.update(doseRepo.findById(id08).orElseThrow().withSyncState(CLEAN));
        var d = svc.markTaken(id08);
        assertThat(d.takenAt()).isEqualTo(dt("2026-10-13T12:00"));
        assertThat(d.syncState()).isEqualTo(DIRTY);
        assertThat(doseRepo.findById(id08)).contains(d);
    }

    @Test
    void markTakenIsIdempotent() {
        svc.markTaken(id08);
        clock.set(dt("2026-10-13T13:00"));
        assertThat(svc.markTaken(id08).takenAt()).isEqualTo(dt("2026-10-13T12:00"));
    }

    @Test
    void markTakenAllowedForFutureDoseToday() {
        assertThat(svc.markTaken(id20).takenAt()).isNotNull();
    }

    @Test
    void unmarkSameDayOk() {
        svc.markTaken(id08);
        assertThat(svc.unmarkTaken(id08).takenAt()).isNull();
    }

    @Test
    void unmarkNextDayRejected() {
        svc.markTaken(id08);
        clock.set(dt("2026-10-14T07:00"));
        assertThatThrownBy(() -> svc.unmarkTaken(id08)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveMissedMarksPastDays() {
        clock.set(dt("2026-10-15T09:00"));
        assertThat(svc.resolveMissed()).isEqualTo(4); // 13th & 14th × 2
        assertThat(doseRepo.findById(id08).orElseThrow().missed()).isTrue();
        assertThat(svc.resolveMissed()).isZero();
    }

    @Test
    void currentPeriodDailyShowsToday() {
        assertThat(svc.currentPeriodDoses(m)).extracting(v -> v.dose().scheduledAt())
                .containsExactly(dt("2026-10-13T08:00"), dt("2026-10-13T20:00"));
    }

    @Test
    void currentPeriodWeeklyShowsWholeWeek() {
        // start 2026-10-12 but created 2026-10-13T12:00, so no dose on the 12th; the 19th is next week
        var weekly = medicines.create(med(pid, d("2026-10-12"), new Course.Ongoing(),
                new Schedule.Weekly(Set.of(MONDAY, THURSDAY), List.of(t("09:00")))));
        var views = svc.currentPeriodDoses(weekly);
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.dose().scheduledAt()).isEqualTo(dt("2026-10-15T09:00"));
            assertThat(v.status()).isEqualTo(PENDING);
        });
    }

    @Test
    void overdueCountForPerson() {
        assertThat(svc.overdueCount(pid)).isEqualTo(1); // 08:00 overdue, 20:00 pending
        svc.markTaken(id08);
        assertThat(svc.overdueCount(pid)).isZero();
    }
}
