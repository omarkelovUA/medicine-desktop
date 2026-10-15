package ua.medicine.service;

import ua.medicine.domain.Person;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;

import java.util.List;

public final class PersonService {

    private final PersonRepository persons;
    private final MedicineRepository medicines;
    private final SyncTrigger syncTrigger;

    public PersonService(PersonRepository persons, MedicineRepository medicines, SyncTrigger syncTrigger) {
        this.persons = persons;
        this.medicines = medicines;
        this.syncTrigger = syncTrigger;
    }

    public List<Person> all() {
        return persons.findAll();
    }

    public Person create(String name, String color) {
        return persons.save(new Person(null, name, color, null, null));
    }

    /** Requests a sync: a child's calendar in the parent's account follows the child's name. */
    public Person update(Person person) {
        Person saved = persons.save(person);
        syncTrigger.requestSync();
        return saved;
    }

    /**
     * @throws PersonHostsChildrenException if others are linked through this person's calendar
     * @throws PersonHasMedicinesException  if the person has active medicines
     */
    public void ensureDeletable(long personId) {
        var children = persons.findHostedBy(personId);
        if (!children.isEmpty()) {
            throw new PersonHostsChildrenException(personId, children.stream().map(Person::name).toList());
        }
        if (medicines.countActiveByPerson(personId) > 0) {
            throw new PersonHasMedicinesException(personId);
        }
    }

    /** Deletes a person who hosts no one and has no active medicines, together with their inactive ones. */
    public void delete(long personId) {
        ensureDeletable(personId);
        medicines.deleteInactiveByPerson(personId);
        persons.delete(personId);
    }
}
