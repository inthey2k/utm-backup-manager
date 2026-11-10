package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupIdentity;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
import de.fjordkommission.utmbackup.storage.BackupState;
import de.fjordkommission.utmbackup.storage.BackupStateWriter;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.BackupStorageMetadataReader;
import de.fjordkommission.utmbackup.storage.BackupStorageReadOnlyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manual multi-delete and the current retention cleanup on a temporary format-2 backup root.
 * The real services and write guard are used, so the tests show what reaches the disk.
 */
class ManualBackupDeletionTest {

    private static final String MARKER = "format.version=2\ncreated.at=2026-10-20T12:00:00+02:00\n";
    private static final VmId VM_A = new VmId("utm", "A.utm");
    private static final VmId VM_B = new VmId("utm", "B.utm");

    @TempDir
    Path root;

    private StableRepository repo;
    private SettingsRepository settings;
    private BackupScanner scanner;
    private BackupService backupService;
    private RetentionService retentionService;

    @BeforeEach
    void setUp() throws IOException {
        repo = mock(StableRepository.class);
        when(repo.findAll()).thenReturn(Map.of());
        settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);

        BackupStorageGuard guard = new BackupStorageGuard(new BackupProperties(root, null, null));
        FakeVmProvider utm = FakeVmProvider.of("utm", List.of(), Map.of());
        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        when(changeStatus.determine(any(VmId.class), any(), anyBoolean())).thenReturn(VmChangeStatus.UNKNOWN);

        scanner = new BackupScanner(
                new BackupProperties(root, null, null),
                repo,
                settings,
                new VmInventory(new VmProviderRegistry(List.of(utm),
                        new VmProperties(List.of("utm"), null))),
                changeStatus,
                guard
        );
        backupService = new BackupService(scanner, repo, guard, new BackupStateWriter());

        VmIdentityService identityService = mock(VmIdentityService.class);
        when(identityService.resolveCanonicalId(any(VmId.class))).thenAnswer(invocation -> invocation.getArgument(0));
        retentionService = new RetentionService(scanner, backupService, guard, identityService);

        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), MARKER, StandardCharsets.UTF_8);
    }

    /** One format-2 batch with a container for each given VM. */
    private void batch(int day, VmId... vms) throws IOException {
        String name = batchName(day);
        Path batch = Files.createDirectories(root.resolve(name));
        List<BackupManifest.VmEntry> entries = new ArrayList<>();

        for (VmId vm : vms) {
            BackupManifest.VmEntry entry = new BackupManifest.VmEntry(vm, vm.localId(), directory(vm));
            Path container = Files.createDirectories(batch.resolve("VMs").resolve(entry.directory()));
            Files.writeString(container.resolve("payload.txt"), "payload");
            entries.add(entry);
        }

        new BackupManifestWriter().writeFile(batch, new BackupManifest(
                BackupFormat.PROVIDER_AWARE,
                Optional.of(OffsetDateTime.of(2026, 10, day, 12, 0, 0, 0, ZoneOffset.ofHours(2))),
                "day " + day,
                entries));
    }

    private void markStable(int day, VmId vm) {
        new BackupStateWriter().writeFile(container(day, vm), new BackupState(true, ""));
    }

    private Path container(int day, VmId vm) {
        return root.resolve(batchName(day)).resolve("VMs").resolve(directory(vm));
    }

    private String id(int day, VmId vm) {
        return BackupIdentity.formatTwoId(batchName(day), vm);
    }

    private static String batchName(int day) {
        return "2026-10-%02d_1200".formatted(day);
    }

    private static String directory(VmId vm) {
        return "utm__" + vm.localId().replace('.', '_');
    }

    private boolean containerExists(int day, VmId vm) {
        return Files.isDirectory(container(day, vm));
    }

    // ------------------------------------------------------------------
    // Manual multi-delete
    // ------------------------------------------------------------------

    @Test
    void severalNormalBackupsCanBeDeletedInOneRequest() throws IOException {
        batch(1, VM_A);
        batch(2, VM_A);
        batch(3, VM_A);

        int deleted = backupService.deleteAll(List.of(id(1, VM_A), id(3, VM_A)));

        assertEquals(2, deleted);
        assertFalse(Files.exists(root.resolve(batchName(1))));
        assertFalse(Files.exists(root.resolve(batchName(3))));
        assertTrue(containerExists(2, VM_A));
    }

    @Test
    void duplicateIdsAreRemovedOnce() throws IOException {
        batch(1, VM_A);

        assertEquals(1, backupService.deleteAll(List.of(id(1, VM_A), id(1, VM_A))));
        assertFalse(Files.exists(root.resolve(batchName(1))));
    }

    @Test
    void stableBackupInTheRequestRejectsTheWholeRequest() throws IOException {
        batch(1, VM_A);
        batch(2, VM_A);
        markStable(2, VM_A);

        List<String> request = List.of(id(1, VM_A), id(2, VM_A));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> backupService.deleteAll(request));

        assertTrue(error.getMessage().contains("Stable"), error.getMessage());
        assertTrue(containerExists(1, VM_A), "the normal backup is kept when the request is refused");
        assertTrue(containerExists(2, VM_A));
    }

    @Test
    void unknownIdRejectsTheWholeRequestBeforeAnyDeletion() throws IOException {
        batch(1, VM_A);

        List<String> request = List.of(id(1, VM_A), "not-a-backup");
        assertThrows(IllegalArgumentException.class, () -> backupService.deleteAll(request));

        assertTrue(containerExists(1, VM_A));
        assertTrue(Files.isDirectory(root.resolve(batchName(1))));
    }

    @Test
    void emptySelectionIsRejected() throws IOException {
        batch(1, VM_A);

        assertThrows(IllegalArgumentException.class, () -> backupService.deleteAll(List.of()));

        assertTrue(containerExists(1, VM_A));
    }

    @Test
    void readOnlyStorageBlocksEveryDeletion() throws IOException {
        batch(1, VM_A);
        batch(2, VM_A);
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME),
                "format.version=3\ncreated.at=2026-10-20T12:00:00+02:00\n", StandardCharsets.UTF_8);

        List<String> request = List.of(id(1, VM_A), id(2, VM_A));
        assertThrows(BackupStorageReadOnlyException.class, () -> backupService.deleteAll(request));

        assertTrue(containerExists(1, VM_A));
        assertTrue(containerExists(2, VM_A));
    }

    @Test
    void deletingOneVmOfAMultiVmBatchKeepsTheOthers() throws IOException {
        batch(1, VM_A, VM_B);

        assertEquals(1, backupService.deleteAll(List.of(id(1, VM_A))));

        assertFalse(containerExists(1, VM_A));
        assertTrue(containerExists(1, VM_B), "the other VM of the batch stays untouched");

        BackupManifestResult manifest = new BackupManifestReader().read(root.resolve(batchName(1)));
        assertTrue(manifest instanceof BackupManifestResult.Parsed(BackupManifest parsed)
                && parsed.vms().size() == 1
                && parsed.vms().getFirst().id().equals(VM_B), "the manifest lists only the remaining VM");
    }

    // ------------------------------------------------------------------
    // Current retention cleanup
    // ------------------------------------------------------------------

    @Test
    void currentCleanupDeletesTheOldestBackupsOfEveryVmAndKeepsTheRest() throws IOException {
        when(settings.getRetentionLimit()).thenReturn(1);
        batch(1, VM_A, VM_B);
        batch(2, VM_A);
        batch(3, VM_A, VM_B);

        // VM A has three normal backups and VM B has two; both are reduced to the newest one.
        assertEquals(3, retentionService.deleteCurrentDeletionCandidates());

        assertFalse(containerExists(1, VM_A));
        assertFalse(containerExists(2, VM_A));
        assertTrue(containerExists(3, VM_A));
        assertFalse(containerExists(1, VM_B));
        assertTrue(containerExists(3, VM_B));
    }

    @Test
    void currentCleanupNeverDeletesStableBackups() throws IOException {
        when(settings.getRetentionLimit()).thenReturn(1);
        batch(1, VM_A);
        batch(2, VM_A);
        batch(3, VM_A);
        markStable(1, VM_A);

        assertEquals(1, retentionService.deleteCurrentDeletionCandidates());

        assertTrue(containerExists(1, VM_A), "the Stable backup stays");
        assertFalse(containerExists(2, VM_A), "the oldest normal backup beyond the limit is removed");
        assertTrue(containerExists(3, VM_A));
    }

    @Test
    void currentCleanupWithoutCandidatesDeletesNothing() throws IOException {
        batch(1, VM_A);
        batch(2, VM_A);

        assertEquals(0, retentionService.deleteCurrentDeletionCandidates());

        assertTrue(containerExists(1, VM_A));
        assertTrue(containerExists(2, VM_A));
    }

    @Test
    void currentCleanupOnReadOnlyStorageDeletesNothing() throws IOException {
        when(settings.getRetentionLimit()).thenReturn(1);
        batch(1, VM_A);
        batch(2, VM_A);
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME),
                "format.version=3\ncreated.at=2026-10-20T12:00:00+02:00\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> retentionService.deleteCurrentDeletionCandidates());

        assertTrue(containerExists(1, VM_A));
    }
}
