package ua.medicine.sync;

import ua.medicine.domain.Person;

@FunctionalInterface
public interface CalendarGatewayFactory {

    /** @throws AuthRequiredException if the person has no usable stored credential */
    CalendarGateway forPerson(Person person);
}
