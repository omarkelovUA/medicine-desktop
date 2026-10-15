package ua.medicine.persistence;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Person;
import ua.medicine.testutil.TestDb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonRepositoryTest {

    private final PersonRepository repo = new PersonRepository(TestDb.create());

    @Test
    void insertFindUpdateDelete() {
        var p = repo.save(new Person(null, "Марія", "#c0397b", null, null));
        assertThat(p.id()).isNotNull();
        repo.save(p.withGoogle("m@gmail.com", "cal123"));
        assertThat(repo.findById(p.id())).get().extracting(Person::googleEmail).isEqualTo("m@gmail.com");
        assertThat(repo.findAll()).hasSize(1);
        repo.delete(p.id());
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void hostFieldsRoundTripAndFindHostedBy() {
        var parent = repo.save(new Person(null, "Олена", "#3a7bd5", "o@gmail.com", "calO"));
        var child = repo.save(new Person(null, "Софія", "#e08a1e", null, null)
                .withHostedGoogle("o@gmail.com", "cal-Ліки Софія", parent.id(), "Ліки Софія"));
        assertThat(repo.findById(child.id())).contains(child);
        assertThat(repo.findById(parent.id()).orElseThrow().calendarHostId()).isNull();
        assertThat(repo.findHostedBy(parent.id())).containsExactly(child);
        assertThat(repo.findHostedBy(child.id())).isEmpty();
    }

    @Test
    void nameUnique() {
        repo.save(new Person(null, "Софія", "#e08a1e", null, null));
        assertThatThrownBy(() -> repo.save(new Person(null, "Софія", "#000", null, null)))
                .isInstanceOf(RuntimeException.class);
    }
}
