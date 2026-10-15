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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private final Database db = TestDb.create();
    private final PersonRepository persons = new PersonRepository(db);
    private final DoseRepository doses = new DoseRepository(db);
    private final MedicineService medicines = new MedicineService(new MedicineRepository(db), doses,
            MutableClock.at("2026-10-13T12:00"), SyncTrigger.NOOP);

    private GoogleLinkService linkService() {
        var auth = new GoogleAuthService(new AppPaths(home));
        return new GoogleLinkService(auth, new GoogleCalendarGatewayFactory(auth), persons, doses, SyncTrigger.NOOP);
    }

    private Person parent() {
        return persons.save(new Person(null, "Олена", "#3a7bd5", "o@gmail.com", "calO"));
    }

    private Person child(String name, Person host) {
        return persons.save(new Person(null, name, "#e08a1e", null, null)
                .withHostedGoogle(host.googleEmail(), "cal-Ліки " + name, host.id(), "Ліки " + name));
    }

    @Test
    void saveHostedLinkStoresHostEmailIdAndCalendarName() {
        var olena = parent();
        var sofia = persons.save(new Person(null, "Софія", "#e08a1e", null, null));
        linkService().saveHostedLink(sofia, olena, "cal-Ліки Софія");
        var saved = persons.findById(sofia.id()).orElseThrow();
        assertThat(saved.googleEmail()).isEqualTo("o@gmail.com");
        assertThat(saved.googleCalendarId()).isEqualTo("cal-Ліки Софія");
        assertThat(saved.calendarHostId()).isEqualTo(olena.id());
        assertThat(saved.calendarName()).isEqualTo("Ліки Софія");
        assertThat(saved.googleLinked()).isTrue();
    }

    @Test
    void hostedRelinkToSameCalendarKeepsDosesCleanOtherCalendarMarksDirty() {
        var olena = parent();
        var sofia = child("Софія", olena);
        var m = medicines.create(med(sofia.id(), d("2026-10-13"), new Course.Ongoing(),
                new Schedule.Daily(List.of(t("08:00")))));
        var dose = doses.findByMedicineBetween(m.id(), dt("2026-10-13T00:00"), dt("2026-10-14T00:00")).getFirst();
        doses.recordPushed(dose.id(), "ev", null, dose.syncState());
        var link = linkService();

        link.saveHostedLink(sofia, olena, "cal-Ліки Софія");
        assertThat(doses.findById(dose.id()).orElseThrow().syncState()).isEqualTo(CLEAN);

        link.saveHostedLink(persons.findById(sofia.id()).orElseThrow(), olena, "other");
        assertThat(doses.findById(dose.id()).orElseThrow().syncState()).isEqualTo(DIRTY);
    }

    @Test
    void linkThroughHostRefusesInvalidHosts() {
        var olena = parent();
        var sofia = child("Софія", olena);
        var unlinked = persons.save(new Person(null, "Петро", "#000000", null, null));
        var marko = persons.save(new Person(null, "Марко", "#111111", null, null));
        var link = linkService();
        assertThatThrownBy(() -> link.linkThroughHost(marko.id(), unlinked.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> link.linkThroughHost(marko.id(), sofia.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> link.linkThroughHost(olena.id(), olena.id()))
                .isInstanceOf(IllegalArgumentException.class);
        var anotherHost = persons.save(new Person(null, "Іван", "#222222", "i@gmail.com", "calI"));
        assertThatThrownBy(() -> link.linkThroughHost(olena.id(), anotherHost.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> link.linkThroughHost(marko.id(), 999))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unlinkingChildKeepsParentToken() throws Exception {
        var olena = parent();
        var sofia = child("Софія", olena);
        Path token = home.resolve("tokens").resolve(String.valueOf(olena.id())).resolve("StoredCredential");
        Files.createDirectories(token.getParent());
        Files.writeString(token, "x");

        linkService().unlink(sofia.id());

        var after = persons.findById(sofia.id()).orElseThrow();
        assertThat(after.googleLinked()).isFalse();
        assertThat(after.hosted()).isFalse();
        assertThat(token).exists();
        assertThat(persons.findById(olena.id()).orElseThrow().googleLinked()).isTrue();
    }

    @Test
    void unlinkingParentUnlinksChildren() throws Exception {
        var olena = parent();
        var sofia = child("Софія", olena);
        var marko = child("Марко", olena);
        Path tokenDir = home.resolve("tokens").resolve(String.valueOf(olena.id()));
        Files.createDirectories(tokenDir);
        Files.writeString(tokenDir.resolve("StoredCredential"), "x");
        var link = linkService();

        link.unlink(olena.id());

        for (var p : List.of(olena, sofia, marko)) {
            assertThat(persons.findById(p.id()).orElseThrow().googleLinked()).as(p.name()).isFalse();
        }
        assertThat(link.hostedBy(olena.id())).isEmpty();
        assertThat(tokenDir).doesNotExist();
    }

    @Test
    void relinkingParentUpdatesChildrenEmail() {
        var olena = parent();
        var sofia = child("Софія", olena);
        linkService().saveLink(olena, "new@gmail.com", "calX");
        var after = persons.findById(sofia.id()).orElseThrow();
        assertThat(after.googleEmail()).isEqualTo("new@gmail.com");
        assertThat(after.calendarHostId()).isEqualTo(olena.id());
        assertThat(after.googleCalendarId()).isEqualTo("cal-Ліки Софія");
    }

    @Test
    void possibleHostsAreLinkedNonHostedOthers() {
        var olena = parent();
        child("Софія", olena);
        var marko = persons.save(new Person(null, "Марко", "#111111", null, null));
        var link = linkService();
        assertThat(link.possibleHosts(marko.id())).extracting(Person::id).containsExactly(olena.id());
        assertThat(link.possibleHosts(olena.id())).isEmpty();
    }

    @Test
    void calendarNameFor() {
        var olena = parent();
        var sofia = child("Софія", olena);
        assertThat(GoogleLinkService.calendarNameFor(olena)).isEqualTo("Ліки");
        assertThat(GoogleLinkService.calendarNameFor(sofia)).isEqualTo("Ліки Софія");
    }

    @Test
    void hostUnlinkedWhileLinkingIsRefused() {
        var olena = parent();
        var sofia = persons.save(new Person(null, "Софія", "#e08a1e", null, null));
        persons.save(olena.withGoogle(null, null)); // the parent was disconnected during the Google call

        assertThatThrownBy(() -> linkService().saveHostedLink(sofia, olena, "cal-Ліки Софія"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(persons.findById(sofia.id()).orElseThrow().googleLinked()).isFalse();
    }
}
