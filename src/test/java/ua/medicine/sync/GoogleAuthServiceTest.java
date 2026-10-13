package ua.medicine.sync;

import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.util.store.FileDataStoreFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.medicine.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleAuthServiceTest {

    private static final String SECRET = """
            {"installed":{"client_id":"id.apps.googleusercontent.com","client_secret":"s",
            "auth_uri":"https://accounts.google.com/o/oauth2/auth","token_uri":"https://oauth2.googleapis.com/token",
            "redirect_uris":["http://localhost"]}}""";

    @TempDir
    Path home;

    private GoogleAuthService service() throws Exception {
        var paths = new AppPaths(home);
        Files.createDirectories(paths.tokens());
        Files.writeString(paths.clientSecret(), SECRET);
        return new GoogleAuthService(paths);
    }

    @Test
    void reauthorizingIgnoresStoredTokenAndForcesConsent() throws Exception {
        var auth = service();
        StoredCredential.getDefaultDataStore(new FileDataStoreFactory(home.resolve("tokens/7").toFile()))
                .set("user", new StoredCredential().setAccessToken("old").setRefreshToken("revoked"));
        var openedUrl = new CompletableFuture<String>();
        auth.setBrowser(openedUrl::complete);

        var attempt = CompletableFuture.runAsync(() -> auth.authorize(7));
        String url = openedUrl.get(5, TimeUnit.SECONDS); // browser opened: stored token was not reused
        assertThat(url).contains("prompt=consent");

        auth.cancelPendingAuthorization();
        assertThat(attempt).failsWithin(5, TimeUnit.SECONDS);
    }

    @Test
    void cancelUnblocksAbandonedSignInSoItCanBeRetried() throws Exception {
        var auth = service();
        var opened = new CompletableFuture<String>();
        auth.setBrowser(opened::complete);
        var first = CompletableFuture.runAsync(() -> auth.authorize(1));
        opened.get(5, TimeUnit.SECONDS);

        auth.cancelPendingAuthorization();
        assertThat(first).failsWithin(5, TimeUnit.SECONDS);
    }
}
