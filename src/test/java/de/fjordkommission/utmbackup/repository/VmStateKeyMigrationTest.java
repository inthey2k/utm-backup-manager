package de.fjordkommission.utmbackup.repository;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.service.VmChangeStatusService;
import de.fjordkommission.utmbackup.service.VmStateImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration of {@code vm_state} keys to canonical {@code providerId:localId} keys,
 * run against a real SQLite file.
 */
class VmStateKeyMigrationTest {

    private static final String BACKED_UP = "BACKED_UP";

    @TempDir
    Path tempDir;

    private BackupProperties properties;
    private VmStateRepository repository;
    private VmStateKeyMigration migration;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        properties = new BackupProperties(
                tempDir.resolve("backups"),
                tempDir.resolve("metadata.db"),
                tempDir.resolve("backups").resolve("backup-state.tsv"));
        repository = new VmStateRepository(properties);
        repository.init();
        new SettingsRepository(properties).init();
        migration = new VmStateKeyMigration(properties);
    }

    private void legacyRow(String key, long mtime, String acknowledgement) throws SQLException {
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO vm_state (vm_name, acknowledged_mtime, acknowledgement, updated_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
            p.setString(1, key);
            p.setLong(2, mtime);
            p.setString(3, acknowledgement);
            p.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + properties.metadataDb());
    }

    /** Every stored key with its stored values, sorted by key. */
    private Map<String, String> rows() throws SQLException {
        Map<String, String> rows = new LinkedHashMap<>();

        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT vm_name, acknowledged_mtime, acknowledgement FROM vm_state ORDER BY vm_name")) {
            while (r.next()) {
                rows.put(r.getString("vm_name"),
                        r.getLong("acknowledged_mtime") + "|" + r.getString("acknowledgement"));
            }
        }

        return rows;
    }

    @Test
    void legacyKeyIsRenamedToCanonicalWithItsValuesUnchanged() throws SQLException {
        legacyRow("Linux Mint 23.utm", 1_700_000_000L, BACKED_UP);

        migration.runMigration();

        assertEquals(Map.of("utm:Linux Mint 23.utm", "1700000000|BACKED_UP"), rows());
    }

    @Test
    void canonicalKeysAreUnchanged() throws SQLException {
        legacyRow("qemu:ArchLinux", 5, BACKED_UP);
        legacyRow("utm:Linux.utm", 6, BACKED_UP);

        migration.runMigration();

        assertEquals(Map.of("qemu:ArchLinux", "5|BACKED_UP", "utm:Linux.utm", "6|BACKED_UP"), rows());
    }

    @Test
    void noKeyIsEverPrefixedTwice() throws SQLException {
        legacyRow("utm:Linux.utm", 6, BACKED_UP);

        migration.runMigration();

        assertFalse(rows().containsKey("utm:utm:Linux.utm"));
        assertTrue(rows().containsKey("utm:Linux.utm"));
    }

    @Test
    void repeatedMigrationIsIdempotent() throws SQLException {
        legacyRow("Linux.utm", 1, BACKED_UP);
        legacyRow("qemu:ArchLinux", 2, BACKED_UP);
        migration.runMigration();
        Map<String, String> afterFirst = rows();

        VmStateKeyMigration.MigrationResult second = migration.runMigration();

        assertEquals(afterFirst, rows());
        assertTrue(second.alreadyMigrated());
    }

    @Test
    void mixedLegacyAndCanonicalRowsAreMigratedOnlyWhereNeeded() throws SQLException {
        legacyRow("Old.utm", 1, "SKIPPED");
        legacyRow("utm:New.utm", 2, BACKED_UP);
        legacyRow("qemu:ArchLinux", 3, BACKED_UP);

        VmStateKeyMigration.MigrationResult result = migration.runMigration();

        assertEquals(Map.of(
                "qemu:ArchLinux", "3|BACKED_UP",
                "utm:New.utm", "2|BACKED_UP",
                "utm:Old.utm", "1|SKIPPED"), rows());
        assertEquals(1, result.migrated());
        assertEquals(0, result.conflicts());
    }

    @Test
    void conflictKeepsTheCanonicalValueAndLeavesTheLegacyRowInPlace() throws SQLException {
        legacyRow("utm:Linux.utm", 100, BACKED_UP);
        legacyRow("Linux.utm", 999, "SKIPPED");

        VmStateKeyMigration.MigrationResult result = migration.runMigration();

        assertEquals(Map.of(
                "utm:Linux.utm", "100|BACKED_UP",
                "Linux.utm", "999|SKIPPED"), rows(), "neither value is overwritten or deleted");
        assertEquals(1, result.conflicts());
    }

    @Test
    void malformedKeysArePreservedUnchanged() throws SQLException {
        legacyRow("", 1, BACKED_UP);
        legacyRow("   ", 2, BACKED_UP);
        legacyRow("utm:", 3, BACKED_UP);
        legacyRow("qemu:", 4, BACKED_UP);

        VmStateKeyMigration.MigrationResult result = migration.runMigration();

        assertEquals(Map.of(
                "", "1|BACKED_UP",
                "   ", "2|BACKED_UP",
                "qemu:", "4|BACKED_UP",
                "utm:", "3|BACKED_UP"), rows());
        assertEquals(4, result.malformed());
    }

    @Test
    void legacyKeyContainingAColonIsPrefixedNotInterpreted() throws SQLException {
        // "Mint" is not a valid provider id, so "Mint:23.utm" is a legacy UTM folder name.
        legacyRow("Mint:23.utm", 7, BACKED_UP);

        migration.runMigration();

        assertEquals(Map.of("utm:Mint:23.utm", "7|BACKED_UP"), rows());
    }

    @Test
    void sameLocalIdUnderDifferentProvidersStaysDistinctAfterMigration() throws SQLException {
        legacyRow("ArchLinux", 10, BACKED_UP);
        legacyRow("qemu:ArchLinux", 20, BACKED_UP);

        migration.runMigration();

        assertEquals(10, repository.find(new VmId("utm", "ArchLinux")).orElseThrow().acknowledgedMtime());
        assertEquals(20, repository.find(new VmId("qemu", "ArchLinux")).orElseThrow().acknowledgedMtime());
    }

    @Test
    void utmChangeStatusIsUnchangedAfterMigration() throws IOException, SQLException {
        Path source = Files.createDirectories(tempDir.resolve("vms").resolve("Linux.utm"));
        Files.writeString(source.resolve("config.plist"), "configuration");
        LocalVm vm = new LocalVm("Linux.utm", "Linux", List.of(source));
        VmChangeStatusService service = new VmChangeStatusService(repository);
        VmId id = new VmId("utm", "Linux.utm");

        service.acknowledgeBackup(id, vm);
        assertEquals(VmChangeStatus.UNCHANGED, service.determine(id, vm, true));

        // Recreate the row a 1.4.x database would contain: same values, legacy key.
        VmStateRepository.State state = repository.find(id).orElseThrow();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM vm_state");
        }
        legacyRow("Linux.utm", state.acknowledgedMtime(), state.acknowledgement().name());

        migration.runMigration();

        assertEquals(VmChangeStatus.UNCHANGED, service.determine(id, vm, true));
    }

    @Test
    void importerWritesCanonicalKeysAndNeverOverwritesAnExistingState() throws IOException, SQLException {
        Files.createDirectories(properties.legacyStateFile().getParent());
        Files.writeString(properties.legacyStateFile(),
                "Linux.utm\t1\tbacked-up\nOld.utm\t2\tskipped\n", StandardCharsets.UTF_8);
        repository.save(new VmId("utm", "Old.utm"), 9, VmStateRepository.Acknowledgement.BACKED_UP);
        migration.runMigration();

        new VmStateImporter(properties, repository).importLegacyState();

        assertEquals(Map.of(
                "utm:Linux.utm", "1|BACKED_UP",
                "utm:Old.utm", "9|BACKED_UP"), rows());
    }

    @Test
    void findReturnsEmptyForAKeyThatWasNotStored() {
        assertEquals(Optional.empty(), repository.find(new VmId("utm", "Missing.utm")));
    }
}
