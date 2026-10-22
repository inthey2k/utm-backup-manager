package de.fjordkommission.utmbackup.repository;

import de.fjordkommission.utmbackup.config.BackupProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time migration of {@code vm_state} keys from provider-local ids to canonical
 * {@code providerId:localId} keys.
 *
 * <p>Rules, applied to each stored key:
 * <ul>
 *   <li>canonical: unchanged, so no key is ever prefixed twice;</li>
 *   <li>malformed (blank, or a valid prefix with nothing after the colon): kept as is;</li>
 *   <li>legacy: renamed to {@code utm:<key>} with its stored values unchanged;</li>
 *   <li>legacy whose canonical key already exists: the canonical row is kept, the
 *       legacy row is left in place and counted as a conflict.</li>
 * </ul>
 * The rows are changed in one transaction, together with a marker in the
 * {@code settings} table. Running it again changes nothing. Backup files and backup
 * ids are not touched, and display names are never used.
 *
 * <p>The bean depends on the repositories, so their tables exist first. The change
 * status importer depends on this bean, so it always runs on canonical keys.
 */
@Component
@DependsOn({"vmStateRepository", "settingsRepository"})
public class VmStateKeyMigration {

    static final String MARKER_KEY = "vm_state.key_format";
    static final String MARKER_VALUE = "canonical";

    private static final Logger log = LoggerFactory.getLogger(VmStateKeyMigration.class);

    private final Path db;

    public VmStateKeyMigration(BackupProperties backupProperties) {
        this.db = backupProperties.metadataDb();
    }

    @PostConstruct
    void migrateOnStartup() {
        MigrationResult result = runMigration();

        if (result.conflicts() > 0 || result.malformed() > 0) {
            log.warn("vm_state migration kept {} legacy row(s) whose canonical key already exists "
                    + "and {} malformed key(s) unchanged", result.conflicts(), result.malformed());
        }
    }

    /**
     * Migrates all legacy keys in one transaction. Exposed for tests.
     *
     * @throws IllegalStateException if the database cannot be migrated; the application
     *                               must not run on a mix of key formats
     */
    MigrationResult runMigration() {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            connection.setAutoCommit(false);
            return migrateInTransaction(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("vm_state keys could not be migrated", e);
        }
    }

    /** Commits on success; rolls back and rethrows on any failure. */
    private MigrationResult migrateInTransaction(Connection connection) throws SQLException {
        try {
            MigrationResult result = migrate(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        }
    }

    private MigrationResult migrate(Connection connection) throws SQLException {
        if (markerPresent(connection)) {
            return new MigrationResult(0, 0, 0, true);
        }

        int migrated = 0;
        int conflicts = 0;
        int malformed = 0;

        for (String key : storedKeys(connection)) {
            if (!VmStateKeys.isCanonical(key)) {
                if (VmStateKeys.isMalformed(key)) {
                    malformed++;
                } else {
                    String canonical = VmStateKeys.canonicalOfLegacy(key);

                    if (exists(connection, canonical)) {
                        conflicts++;
                    } else {
                        rename(connection, key, canonical);
                        migrated++;
                    }
                }
            }
        }

        writeMarker(connection);

        return new MigrationResult(migrated, conflicts, malformed, false);
    }

    /** Sorted, so the migration is deterministic. */
    private static List<String> storedKeys(Connection connection) throws SQLException {
        List<String> keys = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT vm_name FROM vm_state ORDER BY vm_name")) {
            while (rows.next()) {
                keys.add(rows.getString("vm_name"));
            }
        }

        return keys;
    }

    private static boolean exists(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM vm_state WHERE vm_name = ?")) {
            statement.setString(1, key);

            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void rename(Connection connection, String legacyKey, String canonicalKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE vm_state SET vm_name = ? WHERE vm_name = ?")) {
            statement.setString(1, canonicalKey);
            statement.setString(2, legacyKey);
            statement.executeUpdate();
        }
    }

    private static boolean markerPresent(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT setting_value FROM settings WHERE setting_key = ?")) {
            statement.setString(1, MARKER_KEY);

            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && MARKER_VALUE.equals(rows.getString("setting_value"));
            }
        }
    }

    private static void writeMarker(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO settings (setting_key, setting_value, updated_at) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT(setting_key) DO UPDATE SET "
                        + "setting_value = excluded.setting_value, updated_at = CURRENT_TIMESTAMP")) {
            statement.setString(1, MARKER_KEY);
            statement.setString(2, MARKER_VALUE);
            statement.executeUpdate();
        }
    }

    /**
     * @param migrated        legacy rows renamed to canonical keys
     * @param conflicts       legacy rows left in place because their canonical key exists
     * @param malformed       keys kept unchanged because they cannot be interpreted
     * @param alreadyMigrated true when the marker showed a previous migration
     */
    record MigrationResult(int migrated, int conflicts, int malformed, boolean alreadyMigrated) {
    }
}
