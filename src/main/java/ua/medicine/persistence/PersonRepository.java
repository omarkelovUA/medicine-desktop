package ua.medicine.persistence;

import ua.medicine.domain.Person;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PersonRepository {

    private final Database db;

    public PersonRepository(Database db) {
        this.db = db;
    }

    public Person save(Person p) {
        return db.tx(c -> {
            if (p.id() == null) {
                try (var st = c.prepareStatement(
                        "INSERT INTO person(name, color, google_email, google_calendar_id) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bind(st, p);
                    st.executeUpdate();
                    try (var keys = st.getGeneratedKeys()) {
                        keys.next();
                        return p.withId(keys.getLong(1));
                    }
                }
            }
            try (var st = c.prepareStatement(
                    "UPDATE person SET name=?, color=?, google_email=?, google_calendar_id=? WHERE id=?")) {
                bind(st, p);
                st.setLong(5, p.id());
                st.executeUpdate();
                return p;
            }
        });
    }

    public Optional<Person> findById(long id) {
        return db.tx(c -> {
            try (var st = c.prepareStatement("SELECT * FROM person WHERE id=?")) {
                st.setLong(1, id);
                try (var rs = st.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public List<Person> findAll() {
        return db.tx(c -> {
            try (var st = c.prepareStatement("SELECT * FROM person ORDER BY id"); var rs = st.executeQuery()) {
                List<Person> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        });
    }

    public void delete(long id) {
        db.run(c -> {
            try (var st = c.prepareStatement("DELETE FROM person WHERE id=?")) {
                st.setLong(1, id);
                st.executeUpdate();
            }
        });
    }

    private static void bind(PreparedStatement st, Person p) throws SQLException {
        st.setString(1, p.name());
        st.setString(2, p.color());
        st.setString(3, p.googleEmail());
        st.setString(4, p.googleCalendarId());
    }

    private static Person map(ResultSet rs) throws SQLException {
        return new Person(rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                rs.getString("google_email"), rs.getString("google_calendar_id"));
    }
}
