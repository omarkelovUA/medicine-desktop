package ua.medicine.sync;

import ua.medicine.domain.Person;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.service.SyncTrigger;

import java.util.List;

/**
 * Connects a person to their Google account and the "Ліки" calendar in it, or (a child) to a calendar
 * "Ліки <name>" in another person's (the parent's) account.
 */
public final class GoogleLinkService {

    public static final String CALENDAR_NAME = "Ліки";

    private final GoogleAuthService auth;
    private final GoogleCalendarGatewayFactory gateways;
    private final PersonRepository persons;
    private final DoseRepository doses;
    private final SyncTrigger syncTrigger;

    public GoogleLinkService(GoogleAuthService auth, GoogleCalendarGatewayFactory gateways, PersonRepository persons,
                             DoseRepository doses, SyncTrigger syncTrigger) {
        this.auth = auth;
        this.gateways = gateways;
        this.persons = persons;
        this.doses = doses;
        this.syncTrigger = syncTrigger;
    }

    /** Runs the browser consent flow (blocking, always fresh consent), then pushes the person's doses to a new calendar. */
    public Person link(long personId) {
        Person person = find(personId);
        GoogleCalendarGateway gateway = gateways.forCredential(auth.authorize(personId));
        String email = gateway.primaryEmail();
        Person linked = saveLink(person, email, gateway.ensureCalendar(CALENDAR_NAME));
        syncTrigger.requestSync();
        return linked;
    }

    /**
     * Links the child to a calendar "Ліки <name>" in the host's account, reached with the host's token.
     *
     * @throws IllegalArgumentException if the host is not linked with their own account, or the child hosts others
     */
    public Person linkThroughHost(long childId, long hostId) {
        Person child = find(childId);
        Person host = find(hostId);
        if (childId == hostId || !host.googleLinked() || host.hosted() || !hostedBy(childId).isEmpty()) {
            throw new IllegalArgumentException("Person " + hostId + " cannot host person " + childId);
        }
        String calendarId = gateways.forPerson(host).ensureCalendar(childCalendarName(child.name()));
        if (!child.hosted()) {
            auth.forget(childId); // the child's own token, if any, is not used any more
        }
        Person linked = saveHostedLink(child, host, calendarId);
        syncTrigger.requestSync();
        return linked;
    }

    /**
     * As {@link #saveLink}: the doses are pushed again only when the calendar changed.
     *
     * @throws IllegalArgumentException if the host is not linked with their own account any more
     */
    Person saveHostedLink(Person child, Person host, String calendarId) {
        Person current = find(host.id()); // the host may have been unlinked during the Google call
        if (!current.googleLinked() || current.hosted()) {
            throw new IllegalArgumentException("Person " + host.id() + " is no longer linked");
        }
        Person linked = persons.save(child.withHostedGoogle(current.googleEmail(), calendarId, current.id(),
                childCalendarName(child.name())));
        if (!calendarId.equals(child.googleCalendarId())) {
            doses.markDirtyForPerson(child.id());
        }
        return linked;
    }

    /** Persons another person can be linked through: linked with their own account. */
    public List<Person> possibleHosts(long personId) {
        return persons.findAll().stream()
                .filter(p -> p.id() != personId && p.googleLinked() && !p.hosted())
                .toList();
    }

    public List<Person> hostedBy(long hostId) {
        return persons.findHostedBy(hostId);
    }

    public static String childCalendarName(String personName) {
        return CALENDAR_NAME + " " + personName;
    }

    public static String calendarNameFor(Person person) {
        return person.hosted() ? childCalendarName(person.name()) : CALENDAR_NAME;
    }

    /**
     * Re-linking to the same calendar (e.g. after the token expired) leaves the doses as they are: the events
     * are still there, and pushing them all would overwrite marks made on the phone that are not pulled yet.
     */
    Person saveLink(Person person, String email, String calendarId) {
        Person linked = persons.save(person.withGoogle(email, calendarId));
        if (!calendarId.equals(person.googleCalendarId())) {
            doses.markDirtyForPerson(person.id());
        }
        // another account leaves the children's calendars behind: the sync re-creates them in this one
        for (Person child : hostedBy(person.id())) {
            if (!email.equals(child.googleEmail())) {
                persons.save(child.withHostedGoogle(email, child.googleCalendarId(), person.id(),
                        child.calendarName()));
            }
        }
        return linked;
    }

    /**
     * Forgets the token; events already in Google stay there. Event ids are kept so that re-linking the
     * same account updates those events instead of duplicating them (another account answers 404 and
     * the events are created anew). A child's token is the parent's and stays; unlinking a parent unlinks
     * their children too.
     */
    public Person unlink(long personId) {
        Person person = find(personId);
        if (!person.hosted()) {
            auth.forget(personId);
            for (Person child : hostedBy(personId)) {
                persons.save(child.withGoogle(null, null));
            }
        }
        return persons.save(person.withGoogle(null, null));
    }

    private Person find(long personId) {
        return persons.findById(personId).orElseThrow(() -> new IllegalArgumentException("No person " + personId));
    }
}
