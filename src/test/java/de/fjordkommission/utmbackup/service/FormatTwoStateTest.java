package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupIdentity;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
import de.fjordkommission.utmbackup.storage.BackupState;
import de.fjordkommission.utmbackup.storage.BackupStateReader;
import de.fjordkommission.utmbackup.storage.BackupStateResult;
import de.fjordkommission.utmbackup.storage.BackupStateWriter;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.BackupStorageMetadataReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Authority of Stable and note state for format-2 backups. The backup's
 * {@code BACKUP-STATE.properties} decides; SQLite is only a mirror. Uses the real
 * services and guard on a temporary backup root.
 */
class FormatTwoStateTest {

    private static final String MARKER = "format.version=2\ncreated.at=2026-10-20T12:00:00+02:00\n";
    private static final String BATCH = "2026-10-20_1200";
    private static final VmId VM = new VmId("utm", "A.utm");
    private static final String BATCH_ID = BackupIdentity.formatTwoId(BATCH, VM);

    @TempDir
    Path root;

    private StableRepository repo;
    private SettingsRepository settings;
    private BackupScanner scanner;
    private BackupService backupService;
    private BackupStorageGuard guard;
    private RetentionService retentionService;

    @BeforeEach
    void setUp() {
        repo = mock(StableRepository.class);
        when(repo.findAll()).thenReturn(Map.of());
        settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);

        guard = new BackupStorageGuard(new BackupProperties(root, null, null));
        FakeVmProvider utm = FakeVmProvider.of("utm", List.of(), Map.of());
        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        when(changeStatus.determine(any(VmId.class), any(), anyBoolean())).thenReturn(VmChangeStatus.UNKNOWN);

        scanner = new BackupScanner(
                new BackupProperties(root, null, null),
                repo,
                settings,
                new VmInventory(new VmProviderRegistry(List.of(utm),
                        new VmProperties(List.of("utm"), null, null))),
                changeStatus,
                guard
        );
        backupService = new BackupService(scanner, repo, guard, new BackupStateWriter());
        retentionService = new RetentionService(scanner, backupService, guard);

        write(root.resolve(BackupStorageMetadataReader.FILE_NAME), MARKER);
    }

    private Path batch(String name, int day) throws Exception {
        Path batch = Files.createDirectories(root.resolve(name));
        BackupManifest.VmEntry entry = new BackupManifest.VmEntry(VM, "A.utm", "utm__A");
        Path container = Files.createDirectories(batch.resolve("VMs").resolve(entry.directory()));
        Files.writeString(container.resolve("payload.txt"), "payload");

        new BackupManifestWriter().writeFile(batch, new BackupManifest(
                BackupFormat.PROVIDER_AWARE,
                Optional.of(OffsetDateTime.of(2026, 10, day, 12, 0, 0, 0, ZoneOffset.ofHours(2))),
                "day " + day,
                List.of(entry)));
        return batch;
    }

    private Path container(String batchName) {
        return root.resolve(batchName).resolve("VMs").resolve("utm__A");
    }

    private String id(String batchName) {
        return BackupIdentity.formatTwoId(batchName, VM);
    }

    private void writeState(String batchName, BackupState state) {
        new BackupStateWriter().writeFile(container(batchName), state);
    }

    private void writeRawState(String batchName, String text) {
        write(container(batchName).resolve(BackupStateReader.FILE_NAME), text);
    }

    private BackupStateResult stateOf(String batchName) {
        return new BackupStateReader().read(container(batchName));
    }

    private void stubSqlite(String batchName, boolean stable, String note) {
        when(repo.findAll()).thenReturn(Map.of(id(batchName), new StableRepository.Meta(stable, note)));
    }

    private VmBackup backup(String batchName) {
        return scanner.find(id(batchName)).orElseThrow();
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // Authority when scanning
    // ------------------------------------------------------------------

    @Test
    void filesystemStableOverridesSqliteFalse() throws Exception {
        batch(BATCH, 20);
        stubSqlite(BATCH, false, null);
        writeState(BATCH, new BackupState(true, ""));

        assertTrue(backup(BATCH).stable());
    }

    @Test
    void filesystemUnstableOverridesSqliteTrue() throws Exception {
        batch(BATCH, 20);
        stubSqlite(BATCH, true, null);
        writeState(BATCH, new BackupState(false, ""));

        assertFalse(backup(BATCH).stable());
    }

    @Test
    void filesystemNoteOverridesSqliteNote() throws Exception {
        batch(BATCH, 20);
        stubSqlite(BATCH, false, "old note in sqlite");
        writeState(BATCH, new BackupState(false, "note on the medium"));

        assertEquals("note on the medium", backup(BATCH).note());
    }

    @Test
    void missingStateFileUsesDefaultsNotSqlite() throws Exception {
        batch(BATCH, 20);
        stubSqlite(BATCH, true, "stale note");

        VmBackup backup = backup(BATCH);

        assertFalse(backup.stable());
        assertNull(backup.note());
    }

    @Test
    void invalidStateFileDoesNotFallBackToSqliteAndKeepsTheBackupProtected() throws Exception {
        batch(BATCH, 20);
        stubSqlite(BATCH, false, "stale note");
        writeRawState(BATCH, "stable=maybe\n");

        BackupOverview overview = scanner.scan();
        VmBackup backup = backup(BATCH);

        assertTrue(backup.stable(), "an unreadable state keeps the backup protected");
        assertNull(backup.note(), "SQLite note must not be used");
        assertTrue(overview.batchIssues().stream().anyMatch(issue -> issue.reason().contains("stable")));
    }

    // ------------------------------------------------------------------
    // Stable and note writes
    // ------------------------------------------------------------------

    @Test
    void firstStableUpdateCreatesTheStateFileAndMirrorsSqlite() throws Exception {
        batch(BATCH, 20);

        backupService.stable(BATCH_ID, true);

        assertEquals(new BackupStateResult.Parsed(new BackupState(true, "")), stateOf(BATCH));
        verify(repo).save(BATCH_ID, true, "");
    }

    @Test
    void stableUpdateKeepsTheNoteOnTheMedium() throws Exception {
        batch(BATCH, 20);
        writeState(BATCH, new BackupState(false, "keep me"));

        backupService.stable(BATCH_ID, true);

        assertEquals(new BackupStateResult.Parsed(new BackupState(true, "keep me")), stateOf(BATCH));
        verify(repo).save(BATCH_ID, true, "keep me");
    }

    @Test
    void noteUpdateKeepsStableOnTheMedium() throws Exception {
        batch(BATCH, 20);
        writeState(BATCH, new BackupState(true, "old"));

        backupService.note(BATCH_ID, "  new note  ");

        assertEquals(new BackupStateResult.Parsed(new BackupState(true, "new note")), stateOf(BATCH));
        verify(repo).save(BATCH_ID, true, "new note");
    }

    @Test
    void failedFilesystemWriteLeavesSqliteUnchanged() throws Exception {
        batch(BATCH, 20);
        BackupStateWriter failing = mock(BackupStateWriter.class);
        doThrow(new UncheckedIOException(new IOException("disk full")))
                .when(failing).writeFile(any(), any());
        BackupService service = new BackupService(scanner, repo, guard, failing);

        assertThrows(UncheckedIOException.class, () -> service.stable(BATCH_ID, true));
        assertThrows(UncheckedIOException.class, () -> service.note(BATCH_ID, "note"));

        verify(repo, never()).save(anyString(), anyBoolean(), any());
        verify(repo, never()).saveStable(anyString(), anyBoolean());
        verify(repo, never()).saveNote(anyString(), any());
        assertInstanceOf(BackupStateResult.Absent.class, stateOf(BATCH));
    }

    @Test
    void sqliteFailureAfterFilesystemSuccessLeavesTheFilesystemAuthoritative() throws Exception {
        batch(BATCH, 20);
        doThrow(new IllegalStateException("database locked"))
                .when(repo).save(anyString(), anyBoolean(), any());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> backupService.stable(BATCH_ID, true));

        assertTrue(error.getMessage().contains("authoritative"), error.getMessage());
        assertEquals(new BackupStateResult.Parsed(new BackupState(true, "")), stateOf(BATCH));
        assertTrue(backup(BATCH).stable(), "the scan follows the medium, not SQLite");
    }

    @Test
    void invalidStateBlocksMutationWithoutChangingAnything() throws Exception {
        batch(BATCH, 20);
        writeRawState(BATCH, "stable=maybe\n");

        assertThrows(IllegalStateException.class, () -> backupService.stable(BATCH_ID, false));
        assertThrows(IllegalStateException.class, () -> backupService.note(BATCH_ID, "x"));

        assertEquals("stable=maybe\n", Files.readString(container(BATCH).resolve(BackupStateReader.FILE_NAME)));
        verify(repo, never()).save(anyString(), anyBoolean(), any());
    }

    // ------------------------------------------------------------------
    // Delete and retention
    // ------------------------------------------------------------------

    @Test
    void staleSqliteStableTrueDoesNotBlockDeletionWhenTheMediumSaysFalse() throws Exception {
        Path batch = batch(BATCH, 20);
        stubSqlite(BATCH, true, null);
        writeState(BATCH, new BackupState(false, ""));

        backupService.delete(BATCH_ID);

        assertFalse(Files.exists(batch));
    }

    @Test
    void staleSqliteStableFalseDoesNotAllowDeletionWhenTheMediumSaysTrue() throws Exception {
        Path batch = batch(BATCH, 20);
        stubSqlite(BATCH, false, null);
        writeState(BATCH, new BackupState(true, ""));

        assertThrows(IllegalStateException.class, () -> backupService.delete(BATCH_ID));

        assertTrue(Files.isDirectory(container(BATCH)));
        assertTrue(Files.isDirectory(batch));
    }

    @Test
    void invalidStateBlocksDeletion() throws Exception {
        Path batch = batch(BATCH, 20);
        writeRawState(BATCH, "stable=\n");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> backupService.delete(BATCH_ID));

        assertTrue(error.getMessage().contains("cannot be read"), error.getMessage());
        assertTrue(Files.isDirectory(container(BATCH)));
        assertTrue(Files.isDirectory(batch));
    }

    @Test
    void retentionSkipsBackupsMarkedStableOnTheMediumOnly() throws Exception {
        when(settings.getRetentionLimit()).thenReturn(1);
        batch("2026-10-01_1200", 1);
        batch("2026-10-02_1200", 2);
        batch("2026-10-03_1200", 3);
        writeState("2026-10-01_1200", new BackupState(true, ""));
        stubSqlite("2026-10-02_1200", true, null);

        retentionService.deleteBackupsExceedingRetention(List.of(VM));

        assertTrue(Files.isDirectory(container("2026-10-01_1200")), "stable on the medium is kept");
        assertFalse(Files.exists(root.resolve("2026-10-02_1200")), "stale SQLite stable does not protect");
        assertTrue(Files.isDirectory(container("2026-10-03_1200")));
        VmOverview vm = scanner.scan().vms().stream().filter(v -> v.id().equals(VM)).findFirst().orElseThrow();
        assertEquals(2, vm.backupCount());
    }
}
