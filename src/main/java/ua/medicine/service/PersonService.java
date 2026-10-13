package ua.medicine.service;

import ua.medicine.domain.Person;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;

import java.util.List;

public final class PersonService {

    private final PersonRepository persons;
    private final MedicineRepository medicines;

    public PersonService(PersonRepository persons, MedicineRepository medicines) {
        this.persons = persons;
        this.medicines = medicines;
    }

    public List<Person> all() {
        return persons.findAll();
    }

    public Person create(String name, String color) {
        return persons.save(new Person(null, name, color, null, null));
    }

    public Person update(Person person) {
        return persons.save(person);
    }

    /** Deletes a person who has no active medicines, together with their inactive ones. */
    public void delete(long personId) {
        if (medicines.countActiveByPerson(personId) > 0) {
            throw new PersonHasMedicinesException(personId);
        }
        medicines.deleteInactiveByPerson(personId);
        persons.delete(personId);
    }
}
