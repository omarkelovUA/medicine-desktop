package ua.medicine.service;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.DoseStatus;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.schedule.DoseStatusResolver;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Past doses and adherence, including deleted medicines. */
public final class HistoryService {

    private static final LocalDateTime BEGINNING = LocalDate.of(2000, 1, 1).atStartOfDay();

    private final DoseRepository doses;
    private final MedicineRepository medicines;
    private final PersonRepository persons;
    private final Clock clock;

    public HistoryService(DoseRepository doses, MedicineRepository medicines, PersonRepository persons, Clock clock) {
        this.doses = doses;
        this.medicines = medicines;
        this.persons = persons;
        this.clock = clock;
    }

    /** Doses scheduled up to now, newest first. */
    public List<HistoryRow> rows(HistoryFilter filter) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, Person> personById = persons.findAll().stream()
                .collect(Collectors.toMap(Person::id, Function.identity()));
        Map<Long, Medicine> medicineById = medicinesById();
        return pastDoses(filter, now, medicineById).stream()
                .sorted(Comparator.comparing(DoseOccurrence::scheduledAt).reversed())
                .map(d -> {
                    Medicine m = medicineById.get(d.medicineId());
                    return new HistoryRow(d.scheduledAt(), personById.get(m.personId()).name(), m.name(),
                            DoseStatusResolver.statusOf(d, now), d.takenAt());
                })
                .toList();
    }

    /** Per medicine with doses in range: TAKEN vs MISSED counts (OVERDUE not counted yet). Ordered by name. */
    public Map<Medicine, Adherence> adherence(HistoryFilter filter) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, Medicine> medicineById = medicinesById();
        Map<Long, long[]> counts = new LinkedHashMap<>();
        for (DoseOccurrence d : pastDoses(filter, now, medicineById)) {
            long[] c = counts.computeIfAbsent(d.medicineId(), id -> new long[2]);
            DoseStatus status = DoseStatusResolver.statusOf(d, now);
            if (status == DoseStatus.TAKEN) {
                c[0]++;
            } else if (status == DoseStatus.MISSED) {
                c[1]++;
            }
        }
        Map<Medicine, Adherence> result = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparing(e -> medicineById.get(e.getKey()).name()))
                .forEach(e -> result.put(medicineById.get(e.getKey()), new Adherence(e.getValue()[0], e.getValue()[1])));
        return result;
    }

    private List<DoseOccurrence> pastDoses(HistoryFilter filter, LocalDateTime now, Map<Long, Medicine> medicineById) {
        LocalDateTime from = filter.lastDays() == null
                ? BEGINNING
                : now.toLocalDate().minusDays(filter.lastDays()).atStartOfDay();
        return doses.findBetween(from, now.plusNanos(1)).stream()
                .filter(d -> filter.medicineId() == null || filter.medicineId() == d.medicineId())
                .filter(d -> filter.personId() == null
                        || medicineById.get(d.medicineId()).personId() == filter.personId())
                .toList();
    }

    private Map<Long, Medicine> medicinesById() {
        return medicines.findAll().stream().collect(Collectors.toMap(Medicine::id, Function.identity()));
    }
}
