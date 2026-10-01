package de.fjordkommission.utmbackup.repository;

import de.fjordkommission.utmbackup.config.BackupProperties;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Optional;

@Repository
public class VmStateRepository {
    private final Path db;

    public VmStateRepository(BackupProperties backupProperties ) {
        this.db = backupProperties.metadataDb();
    }

    @PostConstruct
    void init() throws Exception {
        Files.createDirectories(db.getParent());
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS vm_state (" +
                    "vm_name TEXT PRIMARY KEY, " +
                    "acknowledged_mtime INTEGER NOT NULL, " +
                    "acknowledgement TEXT NOT NULL, " +
                    "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db);
    }

    public Optional<State> find(String vmName) {
        try (Connection c = connect();
             PreparedStatement p = c.prepareStatement(
                     "SELECT acknowledged_mtime, acknowledgement, updated_at FROM vm_state WHERE vm_name = ?")) {
            p.setString(1, vmName);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new State(
                        r.getLong("acknowledged_mtime"),
                        Acknowledgement.valueOf(r.getString("acknowledgement")),
                        r.getString("updated_at")));
            }
        } catch (SQLException | IllegalArgumentException e) {
            throw new IllegalStateException("VM state could not be read for: " + vmName, e);
        }
    }

    public void save(String vmName, long acknowledgedMtime, Acknowledgement acknowledgement) {
        try (Connection c = connect();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO vm_state (vm_name, acknowledged_mtime, acknowledgement, updated_at) " +
                             "VALUES (?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT(vm_name) DO UPDATE SET " +
                             "acknowledged_mtime = excluded.acknowledged_mtime, " +
                             "acknowledgement = excluded.acknowledgement, updated_at = CURRENT_TIMESTAMP")) {
            p.setString(1, vmName);
            p.setLong(2, acknowledgedMtime);
            p.setString(3, acknowledgement.name());
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("VM state could not be saved for: " + vmName, e);
        }
    }

    public enum Acknowledgement { BACKED_UP, SKIPPED }

    public record State(long acknowledgedMtime, Acknowledgement acknowledgement, String updatedAt) {}
}
