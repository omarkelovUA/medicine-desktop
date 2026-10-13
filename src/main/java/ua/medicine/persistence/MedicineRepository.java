package ua.medicine.persistence;

import ua.medicine.domain.Medicine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MedicineRepository {

    private final Database db;

    public MedicineRepository(Database db) {
        this.db = db;
    }

    public Medicine save(Medicine m) {
        return db.tx(c -> {
            if (m.id() == null) {
                try (var st = c.prepareStatement("""
                        INSERT INTO medicine(person_id, name, dosage, color, start_date, course_amount, course_unit,
                                             schedule_type, schedule_days, schedule_times, active)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
                    bind(st, m);
                    st.executeUpdate();
                    try (var keys = st.getGeneratedKeys()) {
                        keys.next();
                        return m.withId(keys.getLong(1));
                    }
                }
            }
            try (var st = c.prepareStatement("""
                    UPDATE medicine SET person_id=?, name=?, dosage=?, color=?, start_date=?, course_amount=?,
                           course_unit=?, schedule_type=?, schedule_days=?, schedule_times=?, active=?
                    WHERE id=?""")) {
                bind(st, m);
                st.setLong(12, m.id());
                st.executeUpdate();
                return m;
            }
        });
    }

    public Optional<Medicine> findById(long id) {
        return query("SELECT * FROM medicine WHERE id=?", id).stream().findFirst();
    }

    public List<Medicine> findActive() {
        return query("SELECT * FROM medicine WHERE active=1 ORDER BY name, id");
    }

    public List<Medicine> findActiveByPerson(long personId) {
        return query("SELECT * FROM medicine WHERE active=1 AND person_id=? ORDER BY name, id", personId);
    }

    public List<Medicine> findAll() {
        return query("SELECT * FROM medicine ORDER BY name, id");
    }

    public long countActiveByPerson(long personId) {
        return db.tx(c -> {
            try (var st = c.prepareStatement("SELECT COUNT(*) FROM medicine WHERE active=1 AND person_id=?")) {
                st.setLong(1, personId);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** Deletes the person's inactive medicines together with their doses. */
    public void deleteInactiveByPerson(long personId) {
        db.run(c -> {
            try (var doses = prepare(c, """
                    DELETE FROM dose WHERE medicine_id IN (SELECT id FROM medicine WHERE active=0 AND person_id=?)""",
                    personId);
                 var meds = prepare(c, "DELETE FROM medicine WHERE active=0 AND person_id=?", personId)) {
                doses.executeUpdate();
                meds.executeUpdate();
            }
        });
    }

    private List<Medicine> query(String sql, Object... args) {
        return db.tx(c -> {
            try (var st = prepare(c, sql, args); var rs = st.executeQuery()) {
                List<Medicine> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        });
    }

    static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        var st = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            st.setObject(i + 1, args[i]);
        }
        return st;
    }

    private static void bind(PreparedStatement st, Medicine m) throws SQLException {
        st.setLong(1, m.personId());
        st.setString(2, m.name());
        st.setString(3, m.dosage());
        st.setString(4, m.color());
        st.setString(5, m.startDate().toString());
        Integer amount = MedicineCodec.courseAmount(m.course());
        if (amount == null) {
            st.setNull(6, Types.INTEGER);
        } else {
            st.setInt(6, amount);
        }
        st.setString(7, MedicineCodec.courseUnit(m.course()));
        st.setString(8, MedicineCodec.scheduleType(m.schedule()));
        st.setString(9, MedicineCodec.scheduleDays(m.schedule()));
        st.setString(10, MedicineCodec.scheduleTimes(m.schedule()));
        st.setInt(11, m.active() ? 1 : 0);
    }

    private static Medicine map(ResultSet rs) throws SQLException {
        int amount = rs.getInt("course_amount");
        Integer courseAmount = rs.wasNull() ? null : amount;
        return new Medicine(rs.getLong("id"), rs.getLong("person_id"), rs.getString("name"), rs.getString("dosage"),
                rs.getString("color"), LocalDate.parse(rs.getString("start_date")),
                MedicineCodec.course(courseAmount, rs.getString("course_unit")),
                MedicineCodec.schedule(rs.getString("schedule_type"), rs.getString("schedule_days"),
                        rs.getString("schedule_times")),
                rs.getInt("active") == 1);
    }
}
