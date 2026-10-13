package ua.medicine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locations of the app's data under ~/.medicine (or -Dmedicine.home). */
public record AppPaths(Path base) {

    public static AppPaths defaults() {
        String override = System.getProperty("medicine.home");
        Path base = override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".medicine");
        try {
            Files.createDirectories(base.resolve("tokens"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new AppPaths(base);
    }

    public Path db() {
        return base.resolve("medicine.db");
    }

    public Path tokens() {
        return base.resolve("tokens");
    }

    public Path clientSecret() {
        return base.resolve("client_secret.json");
    }
}
