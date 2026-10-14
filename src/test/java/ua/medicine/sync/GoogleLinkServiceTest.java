package ua.medicine.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.medicine.AppPaths;
import ua.medicine.domain.Course;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.persistence.Database;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.service.MedicineService;
import ua.medicine.service.SyncTrigger;
import ua.medicine.testutil.MutableClock;
import ua.medicine.testutil.TestDb;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.SyncState.CLEAN;
import static ua.medicine.domain.SyncState.DIRTY;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class GoogleLinkServiceTest {

    @TempDir
    Path home;

    @Test
    void unlinkKeepsEventIdsSoRelinkingDoesNotDuplicateEvents() {
        Database db = TestDb.create();
        var persons = new PersonRepository(db);
        var doses = new DoseRepository(db);
        var medicines = new MedicineService(new MedicineRepository(db), doses,
                MutableClock.at("2026-10-13T12:00"), SyncTrigger.NOOP);
        var maria = persons.save(new Person(null, "Марія", "#c0397b", "m@gmail.com", "cal"));
        var m = medicines.create(med(maria.id(), d("2026-10-13"), new Course.Ongoing(),
                new Schedule.Daily(List.of(t("08:00")))));
        var dose = doses.findByMedicineBetween(m.id(), dt("2026-10-13T00:00"), dt("2026-10-14T00:00")).getFirst();
        doses.recordPushed(dose.id(), "ev", null, dose.syncState());

        var auth = new GoogleAuthService(new AppPaths(home));
        var link = new GoogleLinkService(auth, new GoogleCalendarGatewayFactory(auth), persons, doses, SyncTrigger.NOOP);
        link.unlink(maria.id());

        assertThat(persons.findById(maria.id()).orElseThrow().googleLinked()).isFalse();
        var after = doses.findById(dose.id()).orElseThrow();
        assertThat(after.calendarEventId()).isEqualTo("ev");
        assertThat(after.syncState()).isEqualTo(CLEAN);
    }

    @Test
    void relinkingTheSameCalendarKeepsDosesCleanSoPhoneMarksAreNotOverwritten() {
        Database db = TestDb.create();
        var persons = new PersonRepository(db);
        var doses = new DoseRepository(db);
        var medicines = new MedicineService(new MedicineRepository(db), doses,
                MutableClock.at("2026-10-13T12:00"), SyncTrigger.NOOP);
        var maria = persons.save(new Person(null, "Марія", "#c0397b", "m@gmail.com", "cal"));
        var m = medicines.create(med(maria.id(), d("2026-10-13"), new Course.Ongoing(),
                new Schedule.Daily(List.of(t("08:00")))));
        var dose = doses.findByMedicineBetween(m.id(), dt("2026-10-13T00:00"), dt("2026-10-14T00:00")).getFirst();
        doses.recordPushed(dose.id(), "ev", null, dose.syncState());
        var auth = new GoogleAuthService(new AppPaths(home));
        var link = new GoogleLinkService(auth, new GoogleCalendarGatewayFactory(auth), persons, doses, SyncTrigger.NOOP);

        link.saveLink(maria, "m@gmail.com", "cal");
        assertThat(doses.findById(dose.id()).orElseThrow().syncState()).isEqualTo(CLEAN);

        link.saveLink(persons.findById(maria.id()).orElseThrow(), "other@gmail.com", "cal2");
        assertThat(doses.findById(dose.id()).orElseThrow().syncState()).isEqualTo(DIRTY);
        assertThat(persons.findById(maria.id()).orElseThrow().googleCalendarId()).isEqualTo("cal2");
    }
}
