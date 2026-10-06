package de.fjordkommission.utmbackup.repository;

import de.fjordkommission.utmbackup.config.BackupProperties;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

@Repository
public class SettingsRepository {

    private static final int DEFAULT_RETENTION_LIMIT = 3;
    private static final String RETENTION_LIMIT_KEY = "retention_limit";

    private final Path db;

    public SettingsRepository(BackupProperties backupProperties) {
        this.db = backupProperties.metadataDb();
    }

    @PostConstruct
    void init() throws IOException, SQLException {
        Files.createDirectories(db.getParent());

        try (
                Connection c = connect();
                Statement s = c.createStatement()
        ) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS settings (" +
                            "setting_key TEXT PRIMARY KEY, " +
                            "setting_value TEXT NOT NULL, " +
                            "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
            );
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db);
    }

    public int getRetentionLimit() {
        try (
                Connection c = connect();
                PreparedStatement p = c.prepareStatement(
                        "SELECT setting_value FROM settings WHERE setting_key = ?"
                )
        ) {
            p.setString(1, RETENTION_LIMIT_KEY);

            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    return DEFAULT_RETENTION_LIMIT;
                }

                return Integer.parseInt(r.getString("setting_value"));
            }

        } catch (SQLException | NumberFormatException e) {
            throw new IllegalStateException(
                    "Retention limit could not be retrieved from the database.", e
            );
        }
    }

    public void setRetentionLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "Retention limit must be a minimum of 1."
            );
        }

        try (
                Connection c = connect();
                PreparedStatement p = c.prepareStatement(
                        "INSERT INTO settings " +
                                "(setting_key, setting_value, updated_at) " +
                                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                                "ON CONFLICT(setting_key) DO UPDATE SET " +
                                "setting_value = excluded.setting_value, " +
                                "updated_at = CURRENT_TIMESTAMP"
                )
        ) {
            p.setString(1, RETENTION_LIMIT_KEY);
            p.setString(2, Integer.toString(limit));
            p.executeUpdate();

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Retention limit could not be stored to the database.", e
            );
        }
    }
}