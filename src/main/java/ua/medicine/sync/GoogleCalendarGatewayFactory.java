package ua.medicine.sync;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.calendar.Calendar;
import ua.medicine.domain.Person;

public final class GoogleCalendarGatewayFactory implements CalendarGatewayFactory {

    private static final String APPLICATION_NAME = "Medicine";

    private final GoogleAuthService auth;

    public GoogleCalendarGatewayFactory(GoogleAuthService auth) {
        this.auth = auth;
    }

    @Override
    public CalendarGateway forPerson(Person person) {
        long owner = person.tokenOwnerId(); // a child's calendar is reached with the parent's token
        Credential credential = auth.load(owner)
                .orElseThrow(() -> new AuthRequiredException("No Google token for person " + owner));
        return forCredential(credential);
    }

    public GoogleCalendarGateway forCredential(Credential credential) {
        return new GoogleCalendarGateway(new Calendar.Builder(auth.transport(), GoogleAuthService.JSON, credential)
                .setApplicationName(APPLICATION_NAME)
                .build());
    }
}
