package ua.medicine.testutil;

import ua.medicine.persistence.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

public final class TestDb {
    private TestDb() {
    }

    /** A migrated database in a fresh temp file. */
    public static Database create() {
        try {
            var file = Files.createTempFile("medicine-test", ".db");
            file.toFile().deleteOnExit();
            var db = new Database("jdbc:sqlite:" + file);
            db.migrate();
            return db;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
