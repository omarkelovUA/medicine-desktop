package ua.medicine.ui;

import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.schedule.TimeScale;

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

    private static final Comparator<Medicine> ORDER =
            Comparator.comparing(Medicine::firstStart).thenComparing(Medicine::name);

    private TimelineRows() {
    }

    /** Medicines overlapping {@code scale}, by start date then name; with {@code grouped}, under person headers. */
    static List<Row> build(List<Person> persons, List<Medicine> medicines, boolean grouped, TimeScale scale) {
        List<Medicine> visible = medicines.stream().filter(m -> overlaps(m, scale)).sorted(ORDER).toList();
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

    private static boolean overlaps(Medicine m, TimeScale scale) {
        return m.firstStart().isBefore(scale.endExclusive())
                && m.endDate().map(end -> !end.isBefore(scale.start())).orElse(true);
    }
}
