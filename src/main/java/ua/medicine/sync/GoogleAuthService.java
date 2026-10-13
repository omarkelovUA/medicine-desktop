package ua.medicine.sync;

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
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
    private AuthorizationCodeInstalledApp.Browser browser = GoogleAuthService::openBrowser;
    private volatile LocalServerReceiver activeReceiver;

    public GoogleAuthService(AppPaths paths) {
        this.paths = paths;
    }

    public boolean clientSecretPresent() {
        return Files.isRegularFile(paths.clientSecret());
    }

    /**
     * Copies an OAuth client JSON downloaded from Google Cloud Console into the data dir.
     *
     * @throws IllegalArgumentException if the file is not a "Desktop app" client
     */
    public void importClientSecret(Path downloaded) {
        String json;
        GoogleClientSecrets secrets;
        try {
            json = Files.readString(downloaded);
            secrets = GoogleClientSecrets.load(JSON, new java.io.StringReader(json));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Not a Google OAuth client file: " + downloaded, e);
        }
        var installed = secrets.getInstalled();
        if (installed == null || installed.getClientId() == null || installed.getClientSecret() == null) {
            throw new IllegalArgumentException("Not a Desktop app OAuth client: " + downloaded);
        }
        try {
            Files.createDirectories(paths.clientSecret().getParent());
            Files.writeString(paths.clientSecret(), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path clientSecretPath() {
        return paths.clientSecret();
    }

    /**
     * Opens the system browser for consent and stores the new token. Blocks until the user finishes or
     * {@link #cancelPendingAuthorization()} is called. Any stored token is dropped first: the library would
     * otherwise silently reuse it, even when it is revoked or expired.
     */
    public Credential authorize(long personId) {
        forget(personId);
        var receiver = new LocalServerReceiver.Builder().setHost("localhost").build();
        activeReceiver = receiver;
        try {
            var flow = flow(personId);
            return new AuthorizationCodeInstalledApp(flow, receiver, browser) {
                @Override
                protected void onAuthorization(AuthorizationCodeRequestUrl url) throws IOException {
                    url.set("prompt", "consent"); // always issue a fresh refresh token
                    super.onAuthorization(url);
                }
            }.authorize(USER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            activeReceiver = null;
        }
    }

    /** Ends a sign-in the user abandoned (closed tab, wrong account) so it can be started again. */
    public void cancelPendingAuthorization() {
        var receiver = activeReceiver;
        if (receiver != null) {
            try {
                receiver.stop();
            } catch (IOException e) {
                log.warn("Stopping the sign-in receiver failed", e);
            }
        }
    }

    void setBrowser(AuthorizationCodeInstalledApp.Browser browser) {
        this.browser = browser;
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

    /** Opens a URL in the system browser; logs it if no browser can be started. */
    public static void openUrl(String url) {
        try {
            openBrowser(url);
        } catch (IOException e) {
            log.warn("Open this URL in a browser: {}", url);
        }
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
