package ua.medicine.sync;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Schedule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class CalendarEventsTest {

    private final Medicine amox = med(d("2026-10-13"), new Course.Ongoing(),
            new Schedule.Daily(List.of(t("08:00"), t("20:00"))));
    private final Medicine amoxNoDosage = new Medicine(null, 1, "Амоксицилін", " ", "#fff", d("2026-10-13"),
            new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00"))), true);
    private final DoseOccurrence dose = DoseOccurrence.planned(1, dt("2026-10-13T08:00"));

    @Test
    void summaryPlainAndTaken() {
        assertThat(CalendarEvents.from(amox, dose).summary()).isEqualTo("💊 Амоксицилін — 500 мг");
        assertThat(CalendarEvents.from(amox, dose.withTakenAt(dt("2026-10-13T08:05"))).summary())
                .isEqualTo("✅ Амоксицилін — 500 мг");
        assertThat(CalendarEvents.from(amoxNoDosage, dose).summary()).isEqualTo("💊 Амоксицилін");
    }

    @Test
    void eventFields() {
        var e = CalendarEvents.from(amox, dose);
        assertThat(e.start()).isEqualTo(dt("2026-10-13T08:00"));
        assertThat(e.durationMinutes()).isEqualTo(15);
        assertThat(e.description()).isEqualTo("2×/день");
        assertThat(e.taken()).isFalse();
    }
}
