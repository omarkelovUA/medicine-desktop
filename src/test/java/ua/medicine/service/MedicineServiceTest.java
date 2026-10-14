package ua.medicine.service;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.persistence.Database;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.testutil.MutableClock;
import ua.medicine.testutil.TestDb;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.domain.SyncState.DELETE_PENDING;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.p;
import static ua.medicine.testutil.T.t;

class MedicineServiceTest {

    private final Database db = TestDb.create();
    private final MedicineRepository medicineRepo = new MedicineRepository(db);
    private final DoseRepository doseRepo = new DoseRepository(db);
    private final MutableClock clock = MutableClock.at("2026-10-13T12:00");
    private final AtomicInteger syncRequests = new AtomicInteger();
    private final MedicineService svc = new MedicineService(medicineRepo, doseRepo, clock, syncRequests::incrementAndGet);
    private final long pid = new PersonRepository(db).save(new Person(null, "Марія", "#c0397b", null, null)).id();
    private final Medicine medicine = med(pid, d("2026-10-13"), new Course.Ongoing(),
            new Schedule.Daily(List.of(t("08:00"), t("20:00"))));

    private List<DoseOccurrence> dosesBetween(Medicine m, String from, String to) {
        return doseRepo.findByMedicineBetween(m.id(), dt(from), dt(to));
    }

    private DoseOccurrence doseAt(Medicine m, String at) {
        LocalDateTime time = dt(at);
        return doseRepo.findByMedicineBetween(m.id(), time, time.plusMinutes(1)).getFirst();
    }

    @Test
    void createGeneratesTodayThroughWindow() {
        var m = svc.create(medicine);
        var doses = dosesBetween(m, "2026-10-01T00:00", "2026-12-01T00:00");
        assertThat(doses).hasSize(30); // 13..27 Oct × 2
        assertThat(doses.getFirst().scheduledAt()).isEqualTo(dt("2026-10-13T08:00"));
        assertThat(doses.getLast().scheduledAt()).isEqualTo(dt("2026-10-27T20:00"));
    }

    @Test
    void createWithPastStartDateGeneratesFromToday() {
        var m = svc.create(med(pid, d("2026-10-01"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00")))));
        assertThat(dosesBetween(m, "2026-10-01T00:00", "2026-10-13T00:00")).isEmpty();
        assertThat(dosesBetween(m, "2026-10-13T00:00", "2026-12-01T00:00")).hasSize(15);
    }

    @Test
    void updateKeepsTakenAndDoesNotDuplicateUnchangedTimes() {
        var m = svc.create(medicine);
        var morning = doseAt(m, "2026-10-13T08:00");
        doseRepo.update(morning.withTakenAt(dt("2026-10-13T08:05")));
        svc.update(m.withSchedule(new Schedule.Daily(List.of(t("08:00"), t("14:00"), t("20:00")))));
        var today = dosesBetween(m, "2026-10-13T00:00", "2026-10-14T00:00");
        assertThat(today).extracting(DoseOccurrence::scheduledAt)
                .containsExactly(dt("2026-10-13T08:00"), dt("2026-10-13T14:00"), dt("2026-10-13T20:00"));
        assertThat(today.getFirst().takenAt()).isEqualTo(dt("2026-10-13T08:05"));
        assertThat(dosesBetween(m, "2026-10-01T00:00", "2026-12-01T00:00")).hasSize(1 + 2 + 14 * 3);
    }

    @Test
    void updateRemovedTimeWithEventBecomesDeletePending() {
        var m = svc.create(medicine);
        doseRepo.update(doseAt(m, "2026-10-14T20:00").withCalendarEventId("ev"));
        svc.update(m.withSchedule(new Schedule.Daily(List.of(t("08:00")))));
        var evening = doseRepo.findNeedingSync(pid).stream()
                .filter(d -> d.scheduledAt().getHour() == 20).toList();
        assertThat(evening).singleElement().satisfies(d -> {
            assertThat(d.scheduledAt()).isEqualTo(dt("2026-10-14T20:00"));
            assertThat(d.syncState()).isEqualTo(DELETE_PENDING);
        });
        assertThat(dosesBetween(m, "2026-10-13T13:00", "2026-12-01T00:00"))
                .allSatisfy(d -> assertThat(d.scheduledAt().getHour()).isEqualTo(8));
    }

    @Test
    void reAddingRemovedTimeRevivesDeletePendingDose() {
        var m = svc.create(medicine);
        doseRepo.update(doseAt(m, "2026-10-14T20:00").withCalendarEventId("ev"));
        svc.update(m.withSchedule(new Schedule.Daily(List.of(t("08:00")))));
        svc.update(m.withSchedule(new Schedule.Daily(List.of(t("08:00"), t("20:00")))));
        assertThat(doseAt(m, "2026-10-14T20:00").calendarEventId()).isEqualTo("ev");
        assertThat(dosesBetween(m, "2026-10-13T00:00", "2026-12-01T00:00")).hasSize(30);
    }

    @Test
    void deleteDeactivatesAndRemovesFutureKeepsPast() {
        var m = svc.create(medicine);
        doseRepo.update(doseAt(m, "2026-10-13T08:00").withTakenAt(dt("2026-10-13T08:05")));
        svc.delete(m.id());
        assertThat(medicineRepo.findById(m.id())).get().extracting(Medicine::active).isEqualTo(false);
        assertThat(dosesBetween(m, "2026-10-01T00:00", "2026-12-01T00:00"))
                .extracting(DoseOccurrence::scheduledAt).containsExactly(dt("2026-10-13T08:00"));
    }

    @Test
    void extendWindowsBackfillsGapAfterAppWasClosed() {
        var m = svc.create(medicine); // up to 2026-10-27
        clock.set(dt("2026-11-05T09:00"));
        svc.extendWindows();
        var all = dosesBetween(m, "2026-10-13T00:00", "2026-12-01T00:00");
        assertThat(all).hasSize(76); // 2026-10-13..2026-11-19 = 38 days × 2, gap days included
    }

    @Test
    void extendWindowsIsIdempotent() {
        var m = svc.create(medicine);
        svc.extendWindows();
        assertThat(dosesBetween(m, "2026-10-01T00:00", "2026-12-01T00:00")).hasSize(30);
    }

    @Test
    void fixedCourseStopsAtEnd() {
        var m = svc.create(med(pid, d("2026-10-13"), new Course.Fixed(3, DAYS),
                new Schedule.Daily(List.of(t("08:00"), t("20:00")))));
        assertThat(dosesBetween(m, "2026-10-01T00:00", "2026-12-01T00:00")).hasSize(6);
    }

    @Test
    void mutationsRequestSync() {
        var m = svc.create(medicine);
        svc.update(m.withSchedule(new Schedule.Daily(List.of(t("09:00")))));
        svc.delete(m.id());
        assertThat(syncRequests).hasValue(3);
    }

    private Medicine twoDays() {
        return med(pid, d("2026-10-13"), new Course.Fixed(2, DAYS), new Schedule.Daily(List.of(t("08:00"), t("20:00"))));
    }

    private static Period oct20() {
        return p("2026-10-20", new Course.Fixed(3, DAYS));
    }

    @Test
    void addingPeriodGeneratesDosesOnlyInside() {
        var m = svc.create(twoDays());
        svc.update(m.withPeriods(List.of(m.lastPeriod(), oct20())));
        assertThat(dosesBetween(m, "2026-10-13T00:00", "2026-12-01T00:00"))
                .extracting(dose -> dose.scheduledAt().getDayOfMonth()).containsOnly(13, 14, 20, 21, 22);
        assertThat(dosesBetween(m, "2026-10-20T00:00", "2026-10-23T00:00")).hasSize(6);
    }

    @Test
    void updateKeepsTakenDosesOfEarlierPeriods() {
        var m = svc.create(twoDays());
        doseRepo.update(doseAt(m, "2026-10-13T08:00").withTakenAt(dt("2026-10-13T08:05")));
        svc.update(m.withPeriods(List.of(m.lastPeriod(), oct20())));
        assertThat(doseAt(m, "2026-10-13T08:00").takenAt()).isEqualTo(dt("2026-10-13T08:05"));
    }

    @Test
    void removingFuturePeriodDropsItsDoses() {
        var m = svc.create(twoDays().withPeriods(List.of(twoDays().lastPeriod(), oct20())));
        doseRepo.update(doseAt(m, "2026-10-20T08:00").withCalendarEventId("ev"));
        svc.update(m.withPeriods(List.of(m.periods().getFirst())));
        assertThat(dosesBetween(m, "2026-10-15T00:00", "2026-12-01T00:00")).isEmpty();
        assertThat(doseRepo.findNeedingSync(pid)).filteredOn(dose -> dose.syncState() == DELETE_PENDING)
                .singleElement().satisfies(dose -> assertThat(dose.scheduledAt()).isEqualTo(dt("2026-10-20T08:00")));
    }

    @Test
    void findSameNameIgnoresCaseAndSpaces() {
        var saved = svc.create(named("Вітамін A"));
        assertThat(svc.findSameName(pid, " вітамін a ")).contains(saved);
        assertThat(svc.findSameName(pid + 1, "Вітамін A")).isEmpty();
    }

    @Test
    void findSameNamePrefersActive() {
        var deleted = svc.create(named("Вітамін A"));
        svc.delete(deleted.id());
        var active = svc.create(named("вітамін a").withPeriods(List.of(p("2026-01-01", new Course.Fixed(2, DAYS)))));
        assertThat(svc.findSameName(pid, "Вітамін A")).contains(active);
    }

    @Test
    void appendPeriodsReactivates() {
        var existing = svc.create(named("Вітамін A"));
        svc.delete(existing.id());
        var form = new Medicine(null, pid, "вітамін a", "2 краплі", "#3a7bd5", List.of(oct20()),
                new Schedule.Daily(List.of(t("09:00"))), true);
        var merged = svc.appendPeriods(existing.id(), form);
        assertThat(merged.active()).isTrue();
        assertThat(merged.periods()).containsExactly(p("2026-10-13", new Course.Fixed(1, DAYS)), oct20());
        assertThat(merged.dosage()).isEqualTo("2 краплі");
        assertThat(merged.name()).isEqualTo("Вітамін A");
        assertThat(medicineRepo.findById(existing.id())).contains(merged);
    }

    @Test
    void appendOverlappingPeriodThrows() {
        var existing = svc.create(named("Вітамін A"));
        var form = named("Вітамін A").withPeriods(List.of(p("2026-10-14", new Course.Fixed(3, DAYS))));
        assertThatThrownBy(() -> svc.appendPeriods(existing.id(), form)).isInstanceOf(IllegalArgumentException.class);
        assertThat(medicineRepo.findById(existing.id()).orElseThrow().periods()).hasSize(1);
    }

    private Medicine named(String name) {
        var m = twoDays();
        return new Medicine(null, pid, name, m.dosage(), m.color(), m.periods(), m.schedule(), true);
    }

    @Test
    void deleteEndsOngoingPeriodToday() {
        var m = svc.create(med(pid, d("2026-01-01"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00")))));
        svc.delete(m.id());
        assertThat(medicineRepo.findById(m.id()).orElseThrow().periods())
                .containsExactly(p("2026-01-01", new Course.Fixed(286, DAYS))); // 1 Jan .. 13 Oct
    }

    @Test
    void deleteDropsPeriodsNotStartedYet() {
        var m = svc.create(twoDays().withPeriods(List.of(twoDays().lastPeriod(), oct20())));
        svc.delete(m.id());
        assertThat(medicineRepo.findById(m.id()).orElseThrow().periods())
                .containsExactly(p("2026-10-13", new Course.Fixed(1, DAYS))); // running period ends today
    }

    @Test
    void deleteKeepsAMedicineThatNeverStarted() {
        var m = svc.create(named("x").withPeriods(List.of(oct20())));
        svc.delete(m.id());
        assertThat(medicineRepo.findById(m.id()).orElseThrow().periods()).containsExactly(oct20());
    }

    @Test
    void restartAfterDeletingOngoingAppends() {
        var m = svc.create(named("Магній").withPeriods(List.of(p("2026-01-01", new Course.Ongoing()))));
        svc.delete(m.id());
        var merged = svc.appendPeriods(m.id(), named("Магній").withPeriods(List.of(oct20())));
        assertThat(merged.periods()).hasSize(2);
    }
}
