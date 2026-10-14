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
        return findNeedingSync(personId, LocalDateTime.of(1, 1, 1, 0, 0));
    }

    /** As {@link #findNeedingSync(long)}, but doses never pushed are included only from {@code pushFrom} on. */
    public List<DoseOccurrence> findNeedingSync(long personId, LocalDateTime pushFrom) {
        return query("""
                SELECT d.* FROM dose d JOIN medicine m ON m.id = d.medicine_id
                WHERE m.person_id=? AND d.sync_state IN ('DIRTY','DELETE_PENDING')
                  AND (d.calendar_event_id IS NOT NULL OR d.scheduled_at>=?)
                ORDER BY d.scheduled_at""", personId, text(pushFrom));
    }

    /** Visible doses of the person that have a calendar event, scheduled in [from, toExclusive). */
    public List<DoseOccurrence> findWithEventForPerson(long personId, LocalDateTime from, LocalDateTime toExclusive) {
        return query("""
                SELECT d.* FROM dose d JOIN medicine m ON m.id = d.medicine_id
                WHERE m.person_id=? AND d.calendar_event_id IS NOT NULL AND d.scheduled_at>=? AND d.scheduled_at<?
                  AND d.sync_state <> 'DELETE_PENDING'
                ORDER BY d.scheduled_at""", personId, text(from), text(toExclusive));
    }

    public void markDirtyForPerson(long personId) {
        execute("""
                UPDATE dose SET sync_state='DIRTY'
                WHERE sync_state='CLEAN' AND medicine_id IN (SELECT id FROM medicine WHERE person_id=?)""", personId);
    }

    // Targeted single-statement writes: the UI thread and the sync thread both write doses,
    // so each change touches only its own columns and never writes back a stale row.

    /** Sets taken_at unless the dose is already taken. */
    public void markTaken(long id, LocalDateTime at) {
        execute("UPDATE dose SET taken_at=?, missed=0, sync_state='DIRTY' WHERE id=? AND taken_at IS NULL",
                text(at), id);
    }

    public void unmarkTaken(long id) {
        execute("UPDATE dose SET taken_at=NULL, sync_state='DIRTY' WHERE id=? AND" + VISIBLE, id);
    }

    /** Marks not-taken doses before {@code t} as missed; returns how many changed. */
    public int markMissedBefore(LocalDateTime t) {
        return db.tx(c -> {
            try (var st = prepare(c, "UPDATE dose SET missed=1 WHERE taken_at IS NULL AND missed=0 AND scheduled_at<? AND"
                    + VISIBLE, text(t))) {
                return st.executeUpdate();
            }
        });
    }

    // Writes for marks pulled from the phone: each applies only to a CLEAN dose, so a change made in the app
    // while the pull was running wins. They return whether the dose changed.

    public boolean markTakenFromPhone(long id, LocalDateTime at) {
        return execute("""
                UPDATE dose SET taken_at=?, missed=0, sync_state='DIRTY'
                WHERE id=? AND taken_at IS NULL AND sync_state='CLEAN'""", text(at), id) > 0;
    }

    public boolean unmarkTakenFromPhone(long id, boolean missed) {
        return execute("""
                UPDATE dose SET taken_at=NULL, missed=?, sync_state='DIRTY'
                WHERE id=? AND taken_at IS NOT NULL AND sync_state='CLEAN'""", missed ? 1 : 0, id) > 0;
    }

    public boolean markDirtyIfClean(long id) {
        return execute("UPDATE dose SET sync_state='DIRTY' WHERE id=? AND sync_state='CLEAN'", id) > 0;
    }

    public void setSyncState(long id, SyncState state) {
        execute("UPDATE dose SET sync_state=? WHERE id=?", state.name(), id);
    }

    /**
     * Stores the calendar event id of a pushed dose. It becomes CLEAN only if nothing changed since it was
     * read for the push ({@code pushedTakenAt}, {@code pushedState}); otherwise it stays as it is.
     */
    public void recordPushed(long id, String eventId, LocalDateTime pushedTakenAt, SyncState pushedState) {
        execute("""
                UPDATE dose SET calendar_event_id=?,
                       sync_state = CASE WHEN taken_at IS ? AND sync_state=? THEN 'CLEAN' ELSE sync_state END
                WHERE id=?""", eventId, text(pushedTakenAt), pushedState.name(), id);
    }

    public void delete(long id) {
        execute("DELETE FROM dose WHERE id=?", id);
    }

    private int execute(String sql, Object... args) {
        return db.tx(c -> {
            try (var st = prepare(c, sql, args)) {
                return st.executeUpdate();
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
