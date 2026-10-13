package ua.medicine.service;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-window reminders: each dose is announced once when its time comes, unless snoozed. */
public final class ReminderService {

    private final DoseRepository doses;
    private final MedicineRepository medicines;
    private final PersonRepository persons;
    private final Clock clock;
    private final LocalDateTime startedAt;
    private final Set<Long> announced = new HashSet<>();
    private final Map<Long, LocalDateTime> snoozedUntil = new HashMap<>();

    public ReminderService(DoseRepository doses, MedicineRepository medicines, PersonRepository persons, Clock clock) {
        this.doses = doses;
        this.medicines = medicines;
        this.persons = persons;
        this.clock = clock;
        this.startedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }

    /** Doses whose time has arrived since startup and that were not announced yet (or whose snooze ended). */
    public synchronized List<Reminder> due() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime from = startedAt.isAfter(dayStart) ? startedAt : dayStart;
        List<Reminder> result = new ArrayList<>();
        for (DoseOccurrence d : doses.findBetween(from, now.plusNanos(1))) {
            if (d.takenAt() != null || d.missed() || !isDue(d.id(), now)) {
                continue;
            }
            Optional<Medicine> medicine = medicines.findById(d.medicineId()).filter(Medicine::active);
            if (medicine.isEmpty()) {
                continue;
            }
            Medicine m = medicine.get();
            String personName = persons.findById(m.personId()).map(Person::name).orElse("");
            announced.add(d.id());
            snoozedUntil.remove(d.id());
            result.add(new Reminder(d.id(), personName, m.name(), m.dosage(), d.scheduledAt().toLocalTime()));
        }
        return result;
    }

    public synchronized void snooze(long doseId, Duration duration) {
        snoozedUntil.put(doseId, LocalDateTime.now(clock).plus(duration));
    }

    private boolean isDue(long doseId, LocalDateTime now) {
        LocalDateTime until = snoozedUntil.get(doseId);
        if (until != null) {
            return !now.isBefore(until);
        }
        return !announced.contains(doseId);
    }
}
