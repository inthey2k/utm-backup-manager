package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupIdentity;
import de.fjordkommission.utmbackup.storage.BackupStateWriter;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.BackupStorageMetadataReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deletion of format-1 (legacy) batches on a temporary backup root. A legacy batch has no
 * {@code BACKUP-INFO.txt} with a format marker and keeps one folder per VM below {@code VMs/}.
 * Older batches also contain a {@code VM Shared Folder} directory next to {@code VMs/}; it is
 * not backed up anymore, but it must not block the removal of the batch.
 */
class LegacyBackupDeletionTest {

    private static final String MARKER = "format.version=2\ncreated.at=2026-10-20T12:00:00+02:00\n";
    private static final String SHARED_FOLDER = "VM Shared Folder";
    private static final String LINT = "Linux Mint 23.utm";
    private static final String ARCH = "Arch Linux.utm";
    private static final String BATCH_ONE = "2026-09-19_1938";
    private static final String BATCH_TWO = "2026-09-20_1000";

    @TempDir
    Path root;

    private StableRepository repo;
    private BackupScanner scanner;
    private BackupService backupService;

    @BeforeEach
    void setUp() throws IOException {
        repo = mock(StableRepository.class);
        when(repo.findAll()).thenReturn(Map.of());
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);

        BackupStorageGuard guard = new BackupStorageGuard(new BackupProperties(root, null, null));
        FakeVmProvider utm = FakeVmProvider.of("utm", List.of(), Map.of());
        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        when(changeStatus.determine(any(VmId.class), any(), anyBoolean())).thenReturn(VmChangeStatus.UNKNOWN);

        scanner = new BackupScanner(
                new BackupProperties(root, null, null),
                repo,
                settings,
                new VmInventory(new VmProviderRegistry(List.of(utm), new VmProperties(List.of("utm"), null))),
                changeStatus,
                guard
        );
        backupService = new BackupService(scanner, repo, guard, new BackupStateWriter());

        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), MARKER, StandardCharsets.UTF_8);
    }

    /** A legacy batch with one folder per VM, each holding a payload file. */
    private Path legacyBatch(String name, String... vmFolders) throws IOException {
        Path batch = Files.createDirectories(root.resolve(name));

        for (String folder : vmFolders) {
            Path vm = Files.createDirectories(batch.resolve("VMs").resolve(folder));
            Files.writeString(vm.resolve("disk.img"), "disk of " + folder);
        }

        return batch;
    }

    /** Legacy Shared Folder content with a file at the top level and nested directories. */
    private void addSharedFolder(Path batch) throws IOException {
        Path shared = Files.createDirectories(batch.resolve(SHARED_FOLDER));
        Files.writeString(shared.resolve("notes.txt"), "shared");
        Path nested = Files.createDirectories(shared.resolve("projects").resolve("deep"));
        Files.writeString(nested.resolve("data.bin"), "deep");
        Files.createDirectories(shared.resolve("empty-subdirectory"));
    }

    private String id(String batchName, String folder) {
        return BackupIdentity.legacyId(batchName, folder);
    }

    private Path vm(String batchName, String folder) {
        return root.resolve(batchName).resolve("VMs").resolve(folder);
    }

    private void markStable(String batchName, String folder) {
        StableRepository.Meta stable = new StableRepository.Meta(true, "");
        when(repo.findAll()).thenReturn(Map.of(id(batchName, folder), stable));
    }

    // ------------------------------------------------------------------
    // Multi-VM legacy batch with Shared Folder
    // ------------------------------------------------------------------

    @Test
    void deletingOneVmKeepsOtherVmsAndSharedFolderInTheBatch() throws IOException {
        addSharedFolder(legacyBatch(BATCH_ONE, LINT, ARCH));

        assertEquals(1, backupService.deleteAll(List.of(id(BATCH_ONE, LINT))));

        assertFalse(Files.exists(vm(BATCH_ONE, LINT)), "the selected VM is removed");
        assertTrue(Files.isRegularFile(vm(BATCH_ONE, ARCH).resolve("disk.img")), "the other VM stays untouched");
        assertTrue(Files.isDirectory(root.resolve(BATCH_ONE)), "the batch stays while a VM remains");
        assertTrue(Files.isRegularFile(root.resolve(BATCH_ONE).resolve(SHARED_FOLDER).resolve("notes.txt")),
                "the Shared Folder stays while other VMs remain");
        assertTrue(Files.isRegularFile(root.resolve(BATCH_ONE).resolve(SHARED_FOLDER)
                .resolve("projects").resolve("deep").resolve("data.bin")));
    }

    @Test
    void deletingTheLastVmRemovesTheWholeBatchIncludingSharedFolder() throws IOException {
        addSharedFolder(legacyBatch(BATCH_ONE, LINT));

        assertEquals(1, backupService.deleteAll(List.of(id(BATCH_ONE, LINT))));

        assertFalse(Files.exists(root.resolve(BATCH_ONE)), "no orphaned batch directory is left behind");
        assertTrue(Files.isDirectory(root), "the backup root itself is kept");
    }

    @Test
    void sharedFolderWithNestedContentDoesNotCauseDirectoryNotEmptyException() throws IOException {
        Path batch = legacyBatch(BATCH_ONE, LINT);
        addSharedFolder(batch);
        Files.createDirectories(batch.resolve(SHARED_FOLDER).resolve("a").resolve("b").resolve("c"));
        Files.writeString(batch.resolve(SHARED_FOLDER).resolve("a").resolve("b").resolve("c").resolve("x"), "x");

        assertEquals(1, backupService.deleteAll(List.of(id(BATCH_ONE, LINT))));

        assertFalse(Files.exists(batch));
    }

    @Test
    void strayNonVmEntryInVmsDoesNotKeepAnEmptyBatch() throws IOException {
        // Finder or another tool may leave a hidden file next to the VM folders.
        Path batch = legacyBatch(BATCH_ONE, LINT);
        Files.writeString(batch.resolve("VMs").resolve(".DS_Store"), "finder metadata");
        addSharedFolder(batch);

        assertEquals(1, backupService.deleteAll(List.of(id(BATCH_ONE, LINT))));

        assertFalse(Files.exists(batch), "the batch has no VM backup left and must be removed");
    }

    // ------------------------------------------------------------------
    // Bulk deletion and safety rules on legacy batches
    // ------------------------------------------------------------------

    @Test
    void bulkDeletionProcessesAllValidLegacyCandidates() throws IOException {
        addSharedFolder(legacyBatch(BATCH_ONE, LINT));
        addSharedFolder(legacyBatch(BATCH_TWO, LINT, ARCH));

        int deleted = backupService.deleteAll(List.of(
                id(BATCH_ONE, LINT),
                id(BATCH_TWO, LINT),
                id(BATCH_TWO, ARCH),
                id(BATCH_TWO, LINT)));

        assertEquals(3, deleted, "the duplicate id is removed once");
        assertFalse(Files.exists(root.resolve(BATCH_ONE)));
        assertFalse(Files.exists(root.resolve(BATCH_TWO)));
    }

    @Test
    void stableLegacyBackupRejectsTheWholeRequestBeforeAnyDeletion() throws IOException {
        legacyBatch(BATCH_ONE, LINT);
        legacyBatch(BATCH_TWO, LINT);
        markStable(BATCH_TWO, LINT);

        List<String> request = List.of(id(BATCH_ONE, LINT), id(BATCH_TWO, LINT));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> backupService.deleteAll(request));

        assertTrue(error.getMessage().contains("Stable"), error.getMessage());
        assertTrue(Files.isDirectory(vm(BATCH_ONE, LINT)), "the normal backup is kept");
        assertTrue(Files.isDirectory(vm(BATCH_TWO, LINT)), "the Stable backup is kept");
    }

    @Test
    void unknownIdRejectsTheWholeRequestBeforeAnyDeletion() throws IOException {
        legacyBatch(BATCH_ONE, LINT);

        List<String> request = List.of(id(BATCH_ONE, LINT), "not-a-backup");
        assertThrows(IllegalArgumentException.class, () -> backupService.deleteAll(request));

        assertTrue(Files.isDirectory(vm(BATCH_ONE, LINT)));
    }

    @Test
    void unsafePathRejectsTheWholeRequestBeforeAnyDeletion() throws IOException {
        // The scanner is replaced so that one listed backup lies outside the backup root.
        Path backupRoot = Files.createDirectories(root.resolve("backups"));
        Files.writeString(backupRoot.resolve(BackupStorageMetadataReader.FILE_NAME), MARKER, StandardCharsets.UTF_8);
        Path valid = Files.createDirectories(backupRoot.resolve(BATCH_ONE).resolve("VMs").resolve(LINT));
        Files.writeString(valid.resolve("disk.img"), "disk");
        Path outside = Files.createDirectories(root.resolve("elsewhere").resolve("VMs").resolve(ARCH));
        Files.writeString(outside.resolve("disk.img"), "disk");

        VmBackup validBackup = new VmBackup(id(BATCH_ONE, LINT), BATCH_ONE, "Linux Mint 23", valid,
                null, "", false, null);
        VmBackup unsafeBackup = new VmBackup("unsafe-backup", BATCH_TWO, "Arch Linux", outside,
                null, "", false, null);

        BackupScanner replacedScanner = mock(BackupScanner.class);
        when(replacedScanner.root()).thenReturn(backupRoot);
        when(replacedScanner.scan()).thenReturn(new BackupOverview(
                List.of(
                        new VmOverview(new VmId("utm", LINT), "UTM", "Linux Mint 23", null,
                                List.of(validBackup), VmStatus.STOPPED, VmChangeStatus.UNKNOWN),
                        new VmOverview(new VmId("utm", ARCH), "UTM", "Arch Linux", null,
                                List.of(unsafeBackup), VmStatus.STOPPED, VmChangeStatus.UNKNOWN)),
                Set.of(), Set.of(), 3, List.of(), List.of()));

        BackupService service = new BackupService(replacedScanner, repo,
                new BackupStorageGuard(new BackupProperties(backupRoot, null, null)), new BackupStateWriter());

        List<String> request = List.of(validBackup.id(), unsafeBackup.id());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.deleteAll(request));

        assertTrue(error.getMessage().contains("Unsafe backup path"), error.getMessage());
        assertTrue(Files.isRegularFile(valid.resolve("disk.img")), "the valid candidate is not removed first");
        assertTrue(Files.isRegularFile(outside.resolve("disk.img")));
    }

    @Test
    void legacyBatchOnReadOnlyStorageIsNotDeleted() throws IOException {
        legacyBatch(BATCH_ONE, LINT);
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME),
                "format.version=3\ncreated.at=2026-10-20T12:00:00+02:00\n", StandardCharsets.UTF_8);

        List<String> request = List.of(id(BATCH_ONE, LINT));
        assertThrows(RuntimeException.class, () -> backupService.deleteAll(request));

        assertTrue(Files.isDirectory(vm(BATCH_ONE, LINT)));
    }
}
