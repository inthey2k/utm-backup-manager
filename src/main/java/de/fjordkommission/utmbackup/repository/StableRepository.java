package de.fjordkommission.utmbackup.repository;

import de.fjordkommission.utmbackup.config.BackupProperties;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

@Repository
public class StableRepository {

    private final Path db;

    public StableRepository(BackupProperties backupProperties) {
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
                    "CREATE TABLE IF NOT EXISTS vm_backup_metadata (" +
                            "backup_id TEXT PRIMARY KEY, " +
                            "stable INTEGER NOT NULL DEFAULT 0, " +
                            "stable_comment TEXT, " +
                            "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
            );
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db);
    }

    public Map<String, Meta> findAll() {
        Map<String, Meta> result = new HashMap<>();

        try (
                Connection c = connect();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(
                        "SELECT backup_id, stable, stable_comment " +
                                "FROM vm_backup_metadata"
                )
        ) {
            while (r.next()) {
                result.put(
                        r.getString("backup_id"),
                        new Meta(
                                r.getInt("stable") != 0,
                                r.getString("stable_comment")
                        )
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Backup metadata could not be read from the database.", e
            );
        }

        return result;
    }

    public void saveStable(String id, boolean stable) {
        Meta existing = findAll().get(id);
        String note = existing != null ? existing.note() : null;

        save(id, stable, note);
    }

    public void saveNote(String id, String note) {
        Meta existing = findAll().get(id);
        boolean stable = existing != null && existing.stable();

        save(id, stable, note);
    }

    public void save(String id, boolean stable, String note) {
        try (
                Connection c = connect();
                PreparedStatement p = c.prepareStatement(
                        "INSERT INTO vm_backup_metadata " +
                                "(backup_id, stable, stable_comment, updated_at) " +
                                "VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                                "ON CONFLICT(backup_id) DO UPDATE SET " +
                                "stable = excluded.stable, " +
                                "stable_comment = excluded.stable_comment, " +
                                "updated_at = CURRENT_TIMESTAMP"
                )
        ) {
            p.setString(1, id);
            p.setInt(2, stable ? 1 : 0);

            if (note == null || note.isBlank()) {
                p.setNull(3, Types.VARCHAR);
            } else {
                p.setString(3, note.trim());
            }

            p.executeUpdate();

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Backup metadata could not be stored to the database.", e
            );
        }
    }

    public void delete(String id) {
        try (
                Connection c = connect();
                PreparedStatement p = c.prepareStatement(
                        "DELETE FROM vm_backup_metadata WHERE backup_id = ?"
                )
        ) {
            p.setString(1, id);
            p.executeUpdate();

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Backup metadata could not be deleted.", e
            );
        }
    }

    public record Meta(
            boolean stable,
            String note
    ) {
    }
}