package ua.medicine.service;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Schedule;
import ua.medicine.persistence.Database;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.testutil.MutableClock;
import ua.medicine.testutil.TestDb;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class PersonServiceTest {

    private final Database db = TestDb.create();
    private final PersonRepository personRepo = new PersonRepository(db);
    private final MedicineRepository medicineRepo = new MedicineRepository(db);
    private final PersonService persons = new PersonService(personRepo, medicineRepo);
    private final MedicineService medicines = new MedicineService(medicineRepo, new DoseRepository(db),
            MutableClock.at("2026-10-13T12:00"), SyncTrigger.NOOP);

    @Test
    void createAndList() {
        persons.create("Марія", "#c0397b");
        persons.create("Софія", "#e08a1e");
        assertThat(persons.all()).extracting(p -> p.name()).containsExactly("Марія", "Софія");
    }

    @Test
    void cannotDeletePersonWithActiveMedicine() {
        long pid = persons.create("Марія", "#c0397b").id();
        medicines.create(med(pid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00")))));
        assertThatThrownBy(() -> persons.delete(pid)).isInstanceOf(PersonHasMedicinesException.class);
        assertThat(persons.all()).hasSize(1);
    }

    @Test
    void deletePersonWithOnlyInactiveMedicinesRemovesThem() {
        long pid = persons.create("Марія", "#c0397b").id();
        var m = medicines.create(med(pid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00")))));
        medicines.delete(m.id());
        persons.delete(pid);
        assertThat(persons.all()).isEmpty();
        assertThat(medicineRepo.findAll()).isEmpty();
    }
}
