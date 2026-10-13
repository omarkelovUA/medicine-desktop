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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class ReminderServiceTest {

    private final Database db = TestDb.create();
    private final PersonRepository personRepo = new PersonRepository(db);
    private final MedicineRepository medicineRepo = new MedicineRepository(db);
    private final DoseRepository doseRepo = new DoseRepository(db);
    private final MutableClock clock = MutableClock.at("2026-10-13T07:55");
    private final MedicineService medicines = new MedicineService(medicineRepo, doseRepo, clock, SyncTrigger.NOOP);
    private final DoseService doses = new DoseService(doseRepo, medicineRepo, clock, SyncTrigger.NOOP);
    private final Person maria = personRepo.save(new Person(null, "Марія", "#c0397b", null, null));
    private final Medicine amox = medicines.create(med(maria.id(), d("2026-10-13"), new Course.Ongoing(),
            new Schedule.Daily(List.of(t("08:00"), t("20:00")))));
    private final ReminderService svc = new ReminderService(doseRepo, medicineRepo, personRepo, clock);
    private final long id08 = doseRepo.findByMedicineBetween(amox.id(), dt("2026-10-13T08:00"), dt("2026-10-13T08:01"))
            .getFirst().id();

    @Test
    void noReminderBeforeTime() {
        assertThat(svc.due()).isEmpty();
    }

    @Test
    void reminderAtTimeOnlyOnce() {
        clock.set(dt("2026-10-13T08:00"));
        assertThat(svc.due()).hasSize(1);
        assertThat(svc.due()).isEmpty();
    }

    @Test
    void noPopupsForDosesBeforeStartup() {
        clock.set(dt("2026-10-13T21:00"));
        var late = new ReminderService(doseRepo, medicineRepo, personRepo, clock);
        assertThat(late.due()).isEmpty();
    }

    @Test
    void snoozeRepeatsAfterTenMinutes() {
        clock.set(dt("2026-10-13T08:00"));
        var r = svc.due().getFirst();
        svc.snooze(r.doseId(), Duration.ofMinutes(10));
        clock.set(dt("2026-10-13T08:09"));
        assertThat(svc.due()).isEmpty();
        clock.set(dt("2026-10-13T08:10"));
        assertThat(svc.due()).extracting(Reminder::doseId).containsExactly(r.doseId());
    }

    @Test
    void takenDoseNotReminded() {
        doses.markTaken(id08);
        clock.set(dt("2026-10-13T08:00"));
        assertThat(svc.due()).isEmpty();
    }

    @Test
    void deletedMedicineNotReminded() {
        clock.set(dt("2026-10-13T07:59"));
        medicines.delete(amox.id()); // removes future doses; nothing left to remind
        clock.set(dt("2026-10-13T08:00"));
        assertThat(svc.due()).isEmpty();
    }

    @Test
    void reminderContents() {
        clock.set(dt("2026-10-13T08:00"));
        var r = svc.due().getFirst();
        assertThat(r.personName()).isEqualTo("Марія");
        assertThat(r.medicineName()).isEqualTo("Амоксицилін");
        assertThat(r.dosage()).isEqualTo("500 мг");
        assertThat(r.time()).isEqualTo(t("08:00"));
    }
}
