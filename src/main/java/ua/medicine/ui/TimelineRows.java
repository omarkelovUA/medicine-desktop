package ua.medicine.ui;

import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.domain.Person;
import ua.medicine.schedule.TimeScale;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Chooses and orders the timeline rows for the visible range. Pure. */
final class TimelineRows {

    sealed interface Row permits PersonHeader, MedicineRow {
    }

    record PersonHeader(Person person) implements Row {
    }

    record MedicineRow(Medicine medicine) implements Row {
    }

    private static Comparator<Medicine> order(TimeScale scale) {
        return Comparator.<Medicine, LocalDate>comparing(m -> visiblePeriods(m, scale).getFirst().start())
                .thenComparing(Medicine::name);
    }

    private TimelineRows() {
    }

    /** Medicines with a period overlapping {@code scale}, by the first such period's start, then name; with {@code grouped}, under person headers. */
    static List<Row> build(List<Person> persons, List<Medicine> medicines, boolean grouped, TimeScale scale) {
        List<Medicine> visible = medicines.stream().filter(m -> !visiblePeriods(m, scale).isEmpty())
                .sorted(order(scale)).toList();
        List<Row> rows = new ArrayList<>();
        if (!grouped) {
            visible.forEach(m -> rows.add(new MedicineRow(m)));
            return rows;
        }
        for (Person p : persons) {
            List<Medicine> own = visible.stream().filter(m -> m.personId() == p.id()).toList();
            if (!own.isEmpty()) {
                rows.add(new PersonHeader(p));
                own.forEach(m -> rows.add(new MedicineRow(m)));
            }
        }
        return rows;
    }

    /** The medicine's periods that overlap {@code scale}, in start order. */
    static List<Period> visiblePeriods(Medicine m, TimeScale scale) {
        return m.periods().stream().filter(p -> overlaps(p, scale)).toList();
    }

    private static boolean overlaps(Period p, TimeScale scale) {
        return p.start().isBefore(scale.endExclusive())
                && p.endDate().map(end -> !end.isBefore(scale.start())).orElse(true);
    }
}
