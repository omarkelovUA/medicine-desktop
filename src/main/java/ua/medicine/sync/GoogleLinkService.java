package ua.medicine.sync;

import ua.medicine.domain.Person;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.service.SyncTrigger;

/** Connects a person to their Google account and the "Ліки" calendar in it. */
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

    /** Runs the browser consent flow (blocking, always fresh consent), then pushes all of the person's doses. */
    public Person link(long personId) {
        Person person = find(personId);
        GoogleCalendarGateway gateway = gateways.forCredential(auth.authorize(personId));
        String email = gateway.primaryEmail();
        String calendarId = gateway.ensureCalendar(CALENDAR_NAME);
        Person linked = persons.save(person.withGoogle(email, calendarId));
        doses.markDirtyForPerson(personId);
        syncTrigger.requestSync();
        return linked;
    }

    /**
     * Forgets the token; events already in Google stay there. Event ids are kept so that re-linking the
     * same account updates those events instead of duplicating them (another account answers 404 and
     * the events are created anew).
     */
    public Person unlink(long personId) {
        Person person = find(personId);
        auth.forget(personId);
        return persons.save(person.withGoogle(null, null));
    }

    private Person find(long personId) {
        return persons.findById(personId).orElseThrow(() -> new IllegalArgumentException("No person " + personId));
    }
}
