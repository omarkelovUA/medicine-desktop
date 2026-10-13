package ua.medicine.persistence;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** One shared SQLite connection; all access goes through {@link #tx}. */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlAction {
        void run(Connection connection) throws SQLException;
    }

    private final String jdbcUrl;
    private final Connection connection;
    private int depth;

    public Database(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (var st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open database " + jdbcUrl, e);
        }
    }

    public void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public Connection connection() {
        return connection;
    }

    /** Runs work in a transaction; nested calls join the outer one. */
    public synchronized <T> T tx(SqlFunction<T> work) {
        boolean outer = depth == 0;
        try {
            if (outer) {
                connection.setAutoCommit(false);
            }
            depth++;
            T result;
            try {
                result = work.apply(connection);
            } finally {
                depth--;
            }
            if (outer) {
                connection.commit();
            }
            return result;
        } catch (SQLException | RuntimeException e) {
            if (outer) {
                rollbackQuietly();
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            if (outer) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // connection is unusable anyway
                }
            }
        }
    }

    public void run(SqlAction work) {
        tx(c -> {
            work.run(c);
            return null;
        });
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // original error is more important
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing on shutdown
        }
    }
}
