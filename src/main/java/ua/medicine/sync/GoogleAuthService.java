package ua.medicine.sync;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.CalendarScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.medicine.AppPaths;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Installed-app OAuth with one token store per person under tokens/<personId>/. */
public final class GoogleAuthService {

    static final JsonFactory JSON = GsonFactory.getDefaultInstance();
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    private static final String USER = "user";

    private final AppPaths paths;
    private NetHttpTransport transport;

    public GoogleAuthService(AppPaths paths) {
        this.paths = paths;
    }

    public boolean clientSecretPresent() {
        return Files.isRegularFile(paths.clientSecret());
    }

    public Path clientSecretPath() {
        return paths.clientSecret();
    }

    /** Opens the system browser for consent and stores the token. Blocks until the user finishes. */
    public Credential authorize(long personId) {
        try {
            var flow = flow(personId);
            var receiver = new LocalServerReceiver.Builder().setHost("localhost").build();
            return new AuthorizationCodeInstalledApp(flow, receiver, GoogleAuthService::openBrowser).authorize(USER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Credential> load(long personId) {
        if (!clientSecretPresent() || !Files.isDirectory(tokenDir(personId))) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(flow(personId).loadCredential(USER));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void forget(long personId) {
        Path dir = tokenDir(personId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    synchronized NetHttpTransport transport() {
        if (transport == null) {
            try {
                transport = GoogleNetHttpTransport.newTrustedTransport();
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException("Cannot create HTTP transport", e);
            }
        }
        return transport;
    }

    private GoogleAuthorizationCodeFlow flow(long personId) throws IOException {
        GoogleClientSecrets secrets;
        try (var reader = Files.newBufferedReader(paths.clientSecret())) {
            secrets = GoogleClientSecrets.load(JSON, reader);
        }
        return new GoogleAuthorizationCodeFlow.Builder(transport(), JSON, secrets, List.of(CalendarScopes.CALENDAR))
                .setDataStoreFactory(new FileDataStoreFactory(tokenDir(personId).toFile()))
                .setAccessType("offline")
                .build();
    }

    private Path tokenDir(long personId) {
        return paths.tokens().resolve(String.valueOf(personId));
    }

    private static void openBrowser(String url) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
            return;
        }
        try {
            new ProcessBuilder("xdg-open", url).start();
        } catch (IOException e) {
            log.warn("Open this URL to authorize Google Calendar: {}", url);
        }
    }
}
