package ua.medicine.persistence;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.testutil.TestDb;

import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.THURSDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.domain.PeriodUnit.MONTHS;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class MedicineRepositoryTest {

    private final Database db = TestDb.create();
    private final MedicineRepository repo = new MedicineRepository(db);
    private final long pid = new PersonRepository(db).save(new Person(null, "Марія", "#c0397b", null, null)).id();

    @Test
    void medicineRoundTripAllScheduleKinds() {
        var daily = med(pid, d("2026-10-13"), new Course.Fixed(10, DAYS), new Schedule.Daily(List.of(t("08:00"), t("20:00"))));
        var weekly = med(pid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Weekly(Set.of(MONDAY, THURSDAY), List.of(t("09:00"))));
        var monthly = med(pid, d("2026-10-13"), new Course.Fixed(3, MONTHS), new Schedule.Monthly(31, List.of(t("09:00"))));
        for (var m : List.of(daily, weekly, monthly)) {
            var saved = repo.save(m);
            assertThat(saved.id()).isNotNull();
            assertThat(repo.findById(saved.id())).contains(saved);
        }
    }

    @Test
    void nullDosageRoundTrips() {
        var m = new Medicine(null, pid, "Вітамін D", null, "#3a7bd5", d("2026-10-13"), new Course.Ongoing(),
                new Schedule.Daily(List.of(t("08:00"))), true);
        var saved = repo.save(m);
        assertThat(repo.findById(saved.id())).contains(saved);
    }

    @Test
    void findActiveByPersonExcludesInactive() {
        var active = repo.save(med(pid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00")))));
        repo.save(med(pid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00")))).withActive(false));
        assertThat(repo.findActiveByPerson(pid)).containsExactly(active);
        assertThat(repo.findActive()).containsExactly(active);
        assertThat(repo.findAll()).hasSize(2);
        assertThat(repo.countActiveByPerson(pid)).isEqualTo(1);
    }

    @Test
    void updateChangesFields() {
        var saved = repo.save(med(pid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00")))));
        repo.save(saved.withActive(false));
        assertThat(repo.findById(saved.id())).get().extracting(Medicine::active).isEqualTo(false);
    }
}
