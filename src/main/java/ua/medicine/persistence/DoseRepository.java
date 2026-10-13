package ua.medicine.persistence;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.SyncState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ua.medicine.persistence.MedicineRepository.prepare;

/** Dose rows. Finders hide DELETE_PENDING rows except {@link #findNeedingSync} and {@link #findFutureUntaken}. */
public final class DoseRepository {

    private static final String VISIBLE = " sync_state <> 'DELETE_PENDING' ";

    private final Database db;

    public DoseRepository(Database db) {
        this.db = db;
    }

    public void insertAll(List<DoseOccurrence> doses) {
        db.run(c -> {
            try (var st = c.prepareStatement("""
                    INSERT OR IGNORE INTO dose(medicine_id, scheduled_at, taken_at, missed, calendar_event_id, sync_state)
                    VALUES (?,?,?,?,?,?)""")) {
                for (var d : doses) {
                    st.setLong(1, d.medicineId());
                    st.setString(2, text(d.scheduledAt()));
                    st.setString(3, text(d.takenAt()));
                    st.setInt(4, d.missed() ? 1 : 0);
                    st.setString(5, d.calendarEventId());
                    st.setString(6, d.syncState().name());
                    st.addBatch();
                }
                st.executeBatch();
            }
        });
    }

    public DoseOccurrence update(DoseOccurrence d) {
        db.run(c -> {
            try (var st = prepare(c, """
                            UPDATE dose SET scheduled_at=?, taken_at=?, missed=?, calendar_event_id=?, sync_state=?
                            WHERE id=?""",
                    text(d.scheduledAt()), text(d.takenAt()), d.missed() ? 1 : 0, d.calendarEventId(),
                    d.syncState().name(), d.id())) {
                st.executeUpdate();
            }
        });
        return d;
    }

    public Optional<DoseOccurrence> findById(long id) {
        return query("SELECT * FROM dose WHERE id=?", id).stream().findFirst();
    }

    public List<DoseOccurrence> findByMedicineBetween(long medicineId, LocalDateTime from, LocalDateTime toExclusive) {
        return query("SELECT * FROM dose WHERE medicine_id=? AND scheduled_at>=? AND scheduled_at<? AND" + VISIBLE
                + "ORDER BY scheduled_at", medicineId, text(from), text(toExclusive));
    }

    public List<DoseOccurrence> findBetween(LocalDateTime from, LocalDateTime toExclusive) {
        return query("SELECT * FROM dose WHERE scheduled_at>=? AND scheduled_at<? AND" + VISIBLE
                + "ORDER BY scheduled_at, id", text(from), text(toExclusive));
    }

    /** Future not-taken doses, including DELETE_PENDING ones so a regenerated time can revive them. */
    public List<DoseOccurrence> findFutureUntaken(long medicineId, LocalDateTime after) {
        return query("SELECT * FROM dose WHERE medicine_id=? AND scheduled_at>? AND taken_at IS NULL "
                + "ORDER BY scheduled_at", medicineId, text(after));
    }

    public List<DoseOccurrence> findUnresolvedBefore(LocalDateTime t) {
        return query("SELECT * FROM dose WHERE taken_at IS NULL AND missed=0 AND scheduled_at<? AND" + VISIBLE
                + "ORDER BY scheduled_at", text(t));
    }

    public Optional<LocalDateTime> maxScheduledAt(long medicineId) {
        return db.tx(c -> {
            try (var st = prepare(c, "SELECT MAX(scheduled_at) FROM dose WHERE medicine_id=?", medicineId);
                 var rs = st.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getString(1)).map(LocalDateTime::parse);
            }
        });
    }

    /** DIRTY and DELETE_PENDING doses of the person's medicines (including DELETE_PENDING). */
    public List<DoseOccurrence> findNeedingSync(long personId) {
        return query("""
                SELECT d.* FROM dose d JOIN medicine m ON m.id = d.medicine_id
                WHERE m.person_id=? AND d.sync_state IN ('DIRTY','DELETE_PENDING')
                ORDER BY d.scheduled_at""", personId);
    }

    public void markDirtyForPerson(long personId) {
        execute("""
                UPDATE dose SET sync_state='DIRTY'
                WHERE sync_state='CLEAN' AND medicine_id IN (SELECT id FROM medicine WHERE person_id=?)""", personId);
    }

    /** After unlinking Google: forget event ids and drop rows only waiting for a remote delete. */
    public void resetSyncForPerson(long personId) {
        db.run(c -> {
            try (var drop = prepare(c, """
                    DELETE FROM dose WHERE sync_state='DELETE_PENDING'
                    AND medicine_id IN (SELECT id FROM medicine WHERE person_id=?)""", personId);
                 var reset = prepare(c, """
                    UPDATE dose SET calendar_event_id=NULL, sync_state='DIRTY'
                    WHERE medicine_id IN (SELECT id FROM medicine WHERE person_id=?)""", personId)) {
                drop.executeUpdate();
                reset.executeUpdate();
            }
        });
    }

    public void delete(long id) {
        execute("DELETE FROM dose WHERE id=?", id);
    }

    private void execute(String sql, Object... args) {
        db.run(c -> {
            try (var st = prepare(c, sql, args)) {
                st.executeUpdate();
            }
        });
    }

    private List<DoseOccurrence> query(String sql, Object... args) {
        return db.tx(c -> {
            try (var st = prepare(c, sql, args); var rs = st.executeQuery()) {
                List<DoseOccurrence> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        });
    }

    private static String text(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    private static LocalDateTime time(String s) {
        return s == null ? null : LocalDateTime.parse(s);
    }

    private static DoseOccurrence map(ResultSet rs) throws SQLException {
        return new DoseOccurrence(rs.getLong("id"), rs.getLong("medicine_id"), time(rs.getString("scheduled_at")),
                time(rs.getString("taken_at")), rs.getInt("missed") == 1, rs.getString("calendar_event_id"),
                SyncState.valueOf(rs.getString("sync_state")));
    }
}
