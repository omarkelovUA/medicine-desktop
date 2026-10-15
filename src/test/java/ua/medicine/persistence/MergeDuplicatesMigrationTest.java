package ua.medicine.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.p;

class MergeDuplicatesMigrationTest {

    private final String url = tempDbUrl();

    private static String tempDbUrl() {
        try {
            var file = Files.createTempFile("medicine-merge", ".db");
            file.toFile().deleteOnExit();
            return "jdbc:sqlite:" + file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target(version).load()
                .migrate();
    }

    private void sql(String... statements) {
        try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
            for (String s : statements) {
                st.execute(s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A version-2 medicine row (dates live in {@code period}). */
    private String medicine(long id, long personId, String name, String dosage, boolean active) {
        return "INSERT INTO medicine(id, person_id, name, dosage, color, schedule_type, schedule_times, active) "
                + "VALUES (" + id + ", " + personId + ", '" + name + "', '" + dosage + "', '#3a7bd5', 'DAILY', '08:00', "
                + (active ? 1 : 0) + ")";
    }

    private static String period(long medicineId, String start, int days) {
        return "INSERT INTO period(medicine_id, start_date, course_amount, course_unit) VALUES ("
                + medicineId + ", '" + start + "', " + days + ", 'DAYS')";
    }

    private static String dose(long medicineId, String at, String eventId) {
        return "INSERT INTO dose(medicine_id, scheduled_at, calendar_event_id, sync_state) VALUES ("
                + medicineId + ", '" + at + "', " + (eventId == null ? "NULL" : "'" + eventId + "'") + ", 'CLEAN')";
    }

    private List<Medicine> migratedMedicines() {
        try (var db = new Database(url)) {
            db.migrate();
            return new MedicineRepository(db).findAll();
        }
    }

    private void persons() {
        sql("INSERT INTO person(id, name, color) VALUES (1, 'Олександр', '#3a7bd5')",
                "INSERT INTO person(id, name, color) VALUES (2, 'Марія', '#c0397b')");
    }

    @Test
    void v2MovesDatesIntoPeriods() {
        migrateTo("1");
        persons();
        sql("INSERT INTO medicine(id, person_id, name, color, start_date, course_amount, course_unit, schedule_type, "
                        + "schedule_times) VALUES (1, 1, 'Магній', '#3a7bd5', '2026-01-01', 30, 'DAYS', 'DAILY', '08:00')",
                "INSERT INTO medicine(id, person_id, name, color, start_date, schedule_type, schedule_times) "
                        + "VALUES (2, 1, 'Вітамін D', '#3a7bd5', '2026-02-01', 'DAILY', '08:00')");
        assertThat(migratedMedicines()).extracting(Medicine::periods).containsExactlyInAnyOrder(
                List.of(p("2026-01-01", new Course.Fixed(30, DAYS))), List.of(p("2026-02-01", new Course.Ongoing())));
    }

    @Test
    void mergesCyrillicCaseInsensitive() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Вітамін A", "1 капсула", false), period(1, "2026-01-01", 30),
                medicine(2, 1, " вітамін a ", "2 краплі", true), period(2, "2026-08-01", 30),
                dose(1, "2026-01-30T08:00", "ev1"), dose(2, "2026-08-05T08:00", "ev2"));
        List<Medicine> all = migratedMedicines();
        assertThat(all).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(2L);
            assertThat(m.periods()).containsExactly(p("2026-01-01", new Course.Fixed(30, DAYS)),
                    p("2026-08-01", new Course.Fixed(30, DAYS)));
            assertThat(m.dosage()).isEqualTo("2 краплі");
            assertThat(m.active()).isTrue();
        });
        try (var db = new Database(url)) {
            assertThat(new DoseRepository(db).findByMedicineBetween(2, dt("2026-01-01T00:00"), dt("2027-01-01T00:00")))
                    .extracting(DoseOccurrence::calendarEventId).containsExactly("ev1", "ev2");
        }
    }

    @Test
    void overlappingPeriodsNotMerged() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Магній", "", true), period(1, "2026-01-01", 30),
                medicine(2, 1, "Магній", "", true), period(2, "2026-01-10", 30));
        assertThat(migratedMedicines()).hasSize(2);
    }

    @Test
    void clashingDoseTimesNotMerged() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Магній", "", true), period(1, "2026-01-01", 3),
                medicine(2, 1, "Магній", "", true), period(2, "2026-02-01", 3),
                dose(1, "2026-01-05T08:00", null), dose(2, "2026-01-05T08:00", null)); // left over from old edits
        assertThat(migratedMedicines()).hasSize(2);
    }

    @Test
    void otherPersonsNotMerged() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Магній", "", true), period(1, "2026-01-01", 3),
                medicine(2, 2, "Магній", "", true), period(2, "2026-02-01", 3));
        assertThat(migratedMedicines()).hasSize(2);
    }

    @Test
    void allInactiveStaysInactive() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Магній", "", false), period(1, "2026-02-01", 3),
                medicine(2, 1, "Магній", "", false), period(2, "2026-01-01", 3));
        assertThat(migratedMedicines()).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(1L); // latest last-period start
            assertThat(m.active()).isFalse();
            assertThat(m.periods()).hasSize(2);
        });
    }

    @Test
    void deletedOngoingIsClippedToItsLastDoseAndMerged() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Магній", "", false),
                "INSERT INTO period(medicine_id, start_date) VALUES (1, '2026-01-01')", // ongoing, deleted in March
                dose(1, "2026-03-10T08:00", null),
                medicine(2, 1, "Магній", "", true), period(2, "2026-08-01", 30));
        assertThat(migratedMedicines()).singleElement().satisfies(m -> assertThat(m.periods()).containsExactly(
                p("2026-01-01", new Course.Fixed(69, DAYS)), p("2026-08-01", new Course.Fixed(30, DAYS))));
    }

    @Test
    void runningMedicineWithOtherScheduleNotMerged() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Вітамін A", "", true), period(1, "2099-01-01", 30), // future doses at 08:00 exist
                dose(1, "2099-01-05T08:00", null),
                medicine(2, 1, "Вітамін A", "", true), period(2, "2099-03-01", 30),
                "UPDATE medicine SET schedule_times='20:00' WHERE id=2");
        assertThat(migratedMedicines()).hasSize(2);
    }

    @Test
    void runningMedicineWithSameScheduleMerged() {
        migrateTo("2");
        persons();
        sql(medicine(1, 1, "Вітамін A", "", true), period(1, "2099-01-01", 30),
                dose(1, "2099-01-05T08:00", null),
                medicine(2, 1, "Вітамін A", "", true), period(2, "2099-03-01", 30));
        assertThat(migratedMedicines()).hasSize(1);
    }

    @Test
    void v4LeavesExistingPersonsLinkedWithTheirOwnAccounts() {
        migrateTo("3");
        sql("INSERT INTO person(id, name, color, google_email, google_calendar_id) "
                + "VALUES (1, 'Олександр', '#3a7bd5', 'o@gmail.com', 'calO')");
        try (var db = new Database(url)) {
            db.migrate();
            var p = new PersonRepository(db).findById(1).orElseThrow();
            assertThat(p.googleCalendarId()).isEqualTo("calO");
            assertThat(p.calendarHostId()).isNull();
            assertThat(p.calendarName()).isNull();
            assertThat(p.tokenOwnerId()).isEqualTo(1L);
        }
    }
}
