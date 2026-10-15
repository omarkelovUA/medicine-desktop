package ua.medicine.service;

import java.util.List;

/** The person's Google account holds the calendars of others, so the person cannot be deleted. */
public class PersonHostsChildrenException extends RuntimeException {

    private final List<String> childNames;

    public PersonHostsChildrenException(long personId, List<String> childNames) {
        super("Person " + personId + " hosts the calendars of " + childNames);
        this.childNames = List.copyOf(childNames);
    }

    public List<String> childNames() {
        return childNames;
    }
}
