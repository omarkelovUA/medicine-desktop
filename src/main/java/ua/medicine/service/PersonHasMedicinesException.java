package ua.medicine.service;

public class PersonHasMedicinesException extends RuntimeException {

    public PersonHasMedicinesException(long personId) {
        super("Person " + personId + " has active medicines");
    }
}
