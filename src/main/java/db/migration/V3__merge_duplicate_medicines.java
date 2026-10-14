package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import ua.medicine.domain.Course;
import ua.medicine.domain.Period;
import ua.medicine.domain.PeriodUnit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Joins medicines of one person with the same name (ignoring case and surrounding spaces) into one medicine with
 * several periods. Done in Java because SQLite's lower() does not fold Cyrillic. A group whose periods overlap, or
 * whose medicines have a dose at the same time, was taken in parallel on purpose and stays as it is. A deleted
 * medicine's open period counts as ending on its last dose (deleting used to be the only way to stop it). A group
 * is also left alone when a medicine with doses still to come has another schedule than the one that is kept.
 */
public class V3__merge_duplicate_medicines extends BaseJavaMigration {

    /**
     * @param schedule     type|days|times, compared as text
     * @param lastDoseDate date of the latest dose, null if none
     * @param upcoming     has untaken doses after now
     */
    record Row(long id, long personId, String name, boolean active, List<Period> periods, Set<String> doseTimes,
               String schedule, LocalDate lastDoseDate, boolean upcoming) {
    }

    /** {@code periods}: the survivor's complete, sorted period list after the merge. */
    record Merge(long survivorId, List<Long> absorbedIds, boolean active, List<Period> periods) {
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        for (Merge merge : plan(read(c))) {
            update(c, "DELETE FROM period WHERE medicine_id=?", merge.survivorId());
            for (long absorbed : merge.absorbedIds()) {
                update(c, "DELETE FROM period WHERE medicine_id=?", absorbed);
                update(c, "UPDATE dose SET medicine_id=? WHERE medicine_id=?", merge.survivorId(), absorbed);
                update(c, "DELETE FROM medicine WHERE id=?", absorbed);
            }
            for (Period p : merge.periods()) {
                Integer amount = p.course() instanceof Course.Fixed f ? f.amount() : null;
                String unit = p.course() instanceof Course.Fixed f ? f.unit().name() : null;
                update(c, "INSERT INTO period(medicine_id, start_date, course_amount, course_unit) VALUES (?,?,?,?)",
                        merge.survivorId(), p.start().toString(), amount, unit);
            }
            update(c, "UPDATE medicine SET active=? WHERE id=?", merge.active() ? 1 : 0, merge.survivorId());
        }
    }

    static List<Merge> plan(List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row r : rows.stream().map(V3__merge_duplicate_medicines::clipDeleted).toList()) {
            groups.computeIfAbsent(r.personId() + "|" + r.name().strip().toLowerCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(r);
        }
        List<Merge> merges = new ArrayList<>();
        for (List<Row> group : groups.values()) {
            if (group.size() < 2 || overlaps(group) || dosesClash(group)) {
                continue;
            }
            Row survivor = group.stream().max(Comparator.comparing(Row::active)
                    .thenComparing(r -> r.periods().getLast().start())
                    .thenComparing(Row::id)).orElseThrow();
            if (group.stream().anyMatch(r -> r.upcoming() && !r.schedule().equals(survivor.schedule()))) {
                continue; // its planned doses would keep the other schedule's times
            }
            List<Long> absorbed = group.stream().filter(r -> r != survivor).map(Row::id).toList();
            List<Period> periods = group.stream().flatMap(r -> r.periods().stream())
                    .sorted(Comparator.comparing(Period::start)).toList();
            merges.add(new Merge(survivor.id(), absorbed, group.stream().anyMatch(Row::active), periods));
        }
        return merges;
    }

    /** A deleted medicine's last period, if still open or running past its last dose, ends on that dose's date. */
    private static Row clipDeleted(Row r) {
        if (r.active()) {
            return r;
        }
        Period last = r.periods().getLast();
        LocalDate end = r.lastDoseDate() == null || r.lastDoseDate().isBefore(last.start())
                ? last.start() : r.lastDoseDate();
        if (last.endDate().map(e -> !e.isAfter(end)).orElse(false)) {
            return r;
        }
        List<Period> periods = new ArrayList<>(r.periods());
        periods.set(periods.size() - 1, last.withCourse(
                new Course.Fixed((int) ChronoUnit.DAYS.between(last.start(), end) + 1, PeriodUnit.DAYS)));
        return new Row(r.id(), r.personId(), r.name(), false, periods, r.doseTimes(), r.schedule(), r.lastDoseDate(),
                r.upcoming());
    }

    private static boolean overlaps(List<Row> group) {
        List<Period> all = group.stream().flatMap(r -> r.periods().stream())
                .sorted(Comparator.comparing(Period::start)).toList();
        for (int i = 1; i < all.size(); i++) {
            var previousEnd = all.get(i - 1).endDate();
            if (previousEnd.isEmpty() || !all.get(i).start().isAfter(previousEnd.get())) {
                return true;
            }
        }
        return false;
    }

    private static boolean dosesClash(List<Row> group) {
        Set<String> seen = new HashSet<>();
        return group.stream().flatMap(r -> r.doseTimes().stream()).anyMatch(t -> !seen.add(t));
    }

    private static List<Row> read(Connection c) throws SQLException {
        Map<Long, List<Period>> periods = new HashMap<>();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT * FROM period ORDER BY start_date")) {
            while (rs.next()) {
                int amount = rs.getInt("course_amount");
                Course course = rs.wasNull()
                        ? new Course.Ongoing()
                        : new Course.Fixed(amount, PeriodUnit.valueOf(rs.getString("course_unit")));
                periods.computeIfAbsent(rs.getLong("medicine_id"), k -> new ArrayList<>())
                        .add(new Period(LocalDate.parse(rs.getString("start_date")), course));
            }
        }
        Map<Long, Set<String>> doseTimes = new HashMap<>();
        Map<Long, LocalDate> lastDose = new HashMap<>();
        Set<Long> upcoming = new HashSet<>();
        String now = LocalDateTime.now().toString();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT medicine_id, scheduled_at, taken_at FROM dose")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String at = rs.getString(2);
                doseTimes.computeIfAbsent(id, k -> new HashSet<>()).add(at);
                lastDose.merge(id, LocalDateTime.parse(at).toLocalDate(), (a, b) -> a.isAfter(b) ? a : b);
                if (rs.getString(3) == null && at.compareTo(now) > 0) {
                    upcoming.add(id);
                }
            }
        }
        List<Row> rows = new ArrayList<>();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT * FROM medicine ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String schedule = rs.getString("schedule_type") + "|" + rs.getString("schedule_days") + "|"
                        + rs.getString("schedule_times");
                rows.add(new Row(id, rs.getLong("person_id"), rs.getString("name"), rs.getInt("active") == 1,
                        periods.getOrDefault(id, List.of()), doseTimes.getOrDefault(id, Set.of()), schedule,
                        lastDose.get(id), upcoming.contains(id)));
            }
        }
        return rows.stream().filter(r -> !r.periods().isEmpty()).toList();
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            st.executeUpdate();
        }
    }
}
