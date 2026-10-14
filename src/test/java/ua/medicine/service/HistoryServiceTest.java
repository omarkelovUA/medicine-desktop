package ua.medicine.service;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.persistence.Database;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.testutil.MutableClock;
import ua.medicine.testutil.TestDb;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.DoseOccurrence.planned;
import static ua.medicine.domain.DoseStatus.MISSED;
import static ua.medicine.domain.DoseStatus.OVERDUE;
import static ua.medicine.domain.DoseStatus.TAKEN;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.p;
import static ua.medicine.testutil.T.t;

class HistoryServiceTest {

    private final Database db = TestDb.create();
    private final PersonRepository personRepo = new PersonRepository(db);
    private final MedicineRepository medicineRepo = new MedicineRepository(db);
    private final DoseRepository doseRepo = new DoseRepository(db);
    private final MutableClock clock = MutableClock.at("2026-10-13T12:00");
    private final MedicineService medicines = new MedicineService(medicineRepo, doseRepo, clock, SyncTrigger.NOOP);
    private final HistoryService history = new HistoryService(doseRepo, medicineRepo, personRepo, clock);
    private final Person maria = personRepo.save(new Person(null, "Марія", "#c0397b", null, null));
    private final Medicine amox = medicines.create(med(maria.id(), d("2026-10-01"), new Course.Ongoing(),
            new Schedule.Daily(List.of(t("08:00"), t("20:00")))));

    {
        doseRepo.insertAll(List.of(
                taken("2026-10-10T08:00"), taken("2026-10-11T08:00"), taken("2026-10-12T08:00"),
                planned(amox.id(), dt("2026-10-12T20:00"))));
    }

    private DoseOccurrence taken(String at) {
        return planned(amox.id(), dt(at)).withTakenAt(dt(at).plusMinutes(5));
    }

    @Test
    void rowsNewestFirstWithoutFutureDoses() {
        var rows = history.rows(new HistoryFilter(null, null, null));
        assertThat(rows).extracting(HistoryRow::scheduledAt).containsExactly(
                dt("2026-10-13T08:00"), dt("2026-10-12T20:00"), dt("2026-10-12T08:00"),
                dt("2026-10-11T08:00"), dt("2026-10-10T08:00"));
        assertThat(rows).extracting(HistoryRow::status).containsExactly(OVERDUE, MISSED, TAKEN, TAKEN, TAKEN);
        assertThat(rows.getFirst().personName()).isEqualTo("Марія");
        assertThat(rows.getFirst().medicineName()).isEqualTo("Амоксицилін");
        assertThat(rows.get(2).takenAt()).isEqualTo(dt("2026-10-12T08:05"));
    }

    @Test
    void adherenceCountsTakenAndMissedOnly() {
        var adherence = history.adherence(new HistoryFilter(null, null, null));
        assertThat(adherence).containsOnlyKeys(amox);
        assertThat(adherence.get(amox)).isEqualTo(new Adherence(3, 1));
        assertThat(adherence.get(amox).percent()).hasValue(75);
    }

    @Test
    void lastDaysFilter() {
        doseRepo.insertAll(List.of(taken("2026-10-03T08:00")));
        assertThat(history.rows(new HistoryFilter(null, null, 7))).hasSize(5);
        assertThat(history.rows(new HistoryFilter(null, null, null))).hasSize(6);
    }

    @Test
    void personAndMedicineFilters() {
        var other = personRepo.save(new Person(null, "Софія", "#e08a1e", null, null));
        var vit = medicines.create(med(other.id(), d("2026-10-01"), new Course.Ongoing(),
                new Schedule.Daily(List.of(t("09:00")))));
        assertThat(history.rows(new HistoryFilter(other.id(), null, null)))
                .extracting(HistoryRow::personName).containsExactly("Софія");
        assertThat(history.rows(new HistoryFilter(null, amox.id(), null))).hasSize(5);
        assertThat(history.rows(new HistoryFilter(maria.id(), null, null))).hasSize(5);
        assertThat(history.adherence(new HistoryFilter(other.id(), null, null))).containsOnlyKeys(vit);
    }

    @Test
    void includesDeletedMedicines() {
        medicines.delete(amox.id());
        assertThat(history.rows(new HistoryFilter(null, null, null))).hasSize(5);
    }

    @Test
    void emptyAdherenceHasNoPercent() {
        assertThat(new Adherence(0, 0).percent()).isEmpty();
    }

    @Test
    void medicineChoicesFollowPersonAndAreDisambiguated() {
        var other = personRepo.save(new Person(null, "Софія", "#e08a1e", null, null));
        var hers = medicines.create(med(other.id(), d("2026-10-01"), new Course.Ongoing(),
                new Schedule.Daily(List.of(t("09:00"))))); // also named "Амоксицилін"
        assertThat(history.medicineChoices(other.id())).containsExactly(
                new HistoryService.MedicineChoice(hers.id(), "Амоксицилін"));
        assertThat(history.medicineChoices(null)).containsExactly(
                new HistoryService.MedicineChoice(amox.id(), "Амоксицилін (Марія)"),
                new HistoryService.MedicineChoice(hers.id(), "Амоксицилін (Софія)"));
    }

    private Medicine vitaminA() {
        return medicineRepo.save(med(maria.id(), d("2026-01-01"), new Course.Fixed(30, PeriodUnit.DAYS),
                new Schedule.Daily(List.of(t("08:00")))).withPeriods(List.of(
                p("2026-01-01", new Course.Fixed(30, PeriodUnit.DAYS)), p("2026-08-01", new Course.Ongoing()))));
    }

    private HistoryRow onlyRow(Medicine m) {
        return history.rows(new HistoryFilter(null, m.id(), null)).getFirst();
    }

    @Test
    void rowCarriesPeriodRange() {
        var m = vitaminA();
        doseRepo.insertAll(List.of(planned(m.id(), dt("2026-01-05T08:00"))));
        assertThat(onlyRow(m).period()).isEqualTo("01.01–30.01.2026");
    }

    @Test
    void doseOutsidePeriodsHasEmptyPeriod() {
        var m = vitaminA();
        doseRepo.insertAll(List.of(planned(m.id(), dt("2026-05-01T08:00"))));
        assertThat(onlyRow(m).period()).isEmpty();
    }

    @Test
    void periodsLineListsAll() {
        assertThat(history.periodsLine(vitaminA().id())).contains("Періоди: 01.01–30.01.2026 · 01.08.2026–…");
    }

    @Test
    void periodsLineEmptyForAllMedicines() {
        assertThat(history.periodsLine(null)).isEmpty();
    }
}
