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
 * whose medicines have a dose at the same time, was taken in parallel on purpose and stays as it is.
 */
public class V3__merge_duplicate_medicines extends BaseJavaMigration {

    record Row(long id, long personId, String name, boolean active, List<Period> periods, Set<String> doseTimes) {
    }

    record Merge(long survivorId, List<Long> absorbedIds, boolean active) {
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        for (Merge merge : plan(read(c))) {
            for (long absorbed : merge.absorbedIds()) {
                update(c, "UPDATE period SET medicine_id=? WHERE medicine_id=?", merge.survivorId(), absorbed);
                update(c, "UPDATE dose SET medicine_id=? WHERE medicine_id=?", merge.survivorId(), absorbed);
                update(c, "DELETE FROM medicine WHERE id=?", absorbed);
            }
            update(c, "UPDATE medicine SET active=? WHERE id=?", merge.active() ? 1 : 0, merge.survivorId());
        }
    }

    static List<Merge> plan(List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row r : rows) {
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
            List<Long> absorbed = group.stream().filter(r -> r != survivor).map(Row::id).toList();
            merges.add(new Merge(survivor.id(), absorbed, group.stream().anyMatch(Row::active)));
        }
        return merges;
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
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT medicine_id, scheduled_at FROM dose")) {
            while (rs.next()) {
                doseTimes.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        List<Row> rows = new ArrayList<>();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, person_id, name, active FROM medicine ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                rows.add(new Row(id, rs.getLong("person_id"), rs.getString("name"), rs.getInt("active") == 1,
                        periods.getOrDefault(id, List.of()), doseTimes.getOrDefault(id, Set.of())));
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
