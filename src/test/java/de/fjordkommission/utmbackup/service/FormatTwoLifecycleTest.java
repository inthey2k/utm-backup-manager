package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
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
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Deletion, retention, Stable protection and the marker rule for format-2 batches,
 * with the real services and a real guard on a temporary backup root.
 */
class FormatTwoLifecycleTest {

    private static final String MARKER = "format.version=2\ncreated.at=2026-10-20T12:00:00+02:00\n";

    @TempDir
    Path root;

    private StableRepository repo;
    private SettingsRepository settings;
    private BackupScanner scanner;
    private BackupService backupService;
    private RetentionService retentionService;
    private final BackupManifestReader manifestReader = new BackupManifestReader();

    @BeforeEach
    void setUp() {
        repo = mock(StableRepository.class);
        when(repo.findAll()).thenReturn(Map.of());
        settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);

        BackupStorageGuard guard = new BackupStorageGuard(new BackupProperties(root, null, null));
        FakeVmProvider utm = FakeVmProvider.of("utm", List.of(), Map.of());
        FakeVmProvider qemu = FakeVmProvider.of("qemu", List.of(), Map.of());
        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        when(changeStatus.determine(any(VmId.class), any(), anyBoolean())).thenReturn(VmChangeStatus.UNKNOWN);

        scanner = new BackupScanner(
                new BackupProperties(root, null, null),
                repo,
                settings,
                new VmInventory(new VmProviderRegistry(List.of(utm, qemu),
                        new VmProperties(List.of("utm", "qemu"), null, null))),
                changeStatus,
                guard
        );
        backupService = new BackupService(scanner, repo, guard);
        retentionService = new RetentionService(scanner, backupService, guard);
    }

    /** Writes a supported marker, as production does once a format-2 batch exists. */
    private void marker() throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), MARKER, StandardCharsets.UTF_8);
    }

    private Path batch(String name, int day, BackupManifest.VmEntry... entries) throws IOException {
        Path batch = Files.createDirectories(root.resolve(name));

        for (BackupManifest.VmEntry entry : entries) {
            Path container = Files.createDirectories(batch.resolve("VMs").resolve(entry.directory()));
            Files.writeString(container.resolve("payload.txt"), entry.id().toString());
        }

        new BackupManifestWriter().writeFile(batch, new BackupManifest(
                BackupFormat.PROVIDER_AWARE,
                Optional.of(OffsetDateTime.of(2026, 10, day, 12, 0, 0, 0, ZoneOffset.ofHours(2))),
                "day " + day,
                List.of(entries)));

        return batch;
    }

    private static BackupManifest.VmEntry entry(String provider, String localId, String directory) {
        return new BackupManifest.VmEntry(new VmId(provider, localId), localId, directory);
    }

    private BackupManifest manifestOf(Path batch) {
        BackupManifestResult result = manifestReader.read(batch);
        assertInstanceOf(BackupManifestResult.Parsed.class, result, String.valueOf(result));
        return ((BackupManifestResult.Parsed) result).manifest();
    }

    private String idOf(VmId vmId, String batchName) {
        return BackupIdentity.formatTwoId(batchName, vmId);
    }

    private VmOverview find(VmId id) {
        BackupOverview overview = scanner.scan();
        return overview.vms().stream()
                .filter(vm -> vm.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry for " + id));
    }

    // ------------------------------------------------------------------
    // Deletion
    // ------------------------------------------------------------------

    @Test
    void deletingOneVmLeavesTheOtherVmsOfTheBatchUntouchedAndUpdatesTheManifest() throws IOException {
        marker();
        Path batch = batch("2026-10-20_1200", 20,
                entry("utm", "A.utm", "utm__A"), entry("utm", "B.utm", "utm__B"));

        backupService.delete(idOf(new VmId("utm", "A.utm"), "2026-10-20_1200"));

        assertFalse(Files.exists(batch.resolve("VMs").resolve("utm__A")));
        assertEquals("utm:B.utm", Files.readString(batch.resolve("VMs").resolve("utm__B").resolve("payload.txt")));
        assertEquals(List.of(new VmId("utm", "B.utm")),
                manifestOf(batch).vms().stream().map(BackupManifest.VmEntry::id).toList());
    }

    @Test
    void deletingTheLastVmRemovesTheEmptyBatch() throws IOException {
        marker();
        Path batch = batch("2026-10-20_1200", 20, entry("utm", "A.utm", "utm__A"));

        backupService.delete(idOf(new VmId("utm", "A.utm"), "2026-10-20_1200"));

        assertFalse(Files.exists(batch));
    }

    @Test
    void stableBackupOfFormatTwoCannotBeDeleted() throws IOException {
        marker();
        Path batch = batch("2026-10-20_1200", 20, entry("utm", "A.utm", "utm__A"));
        String id = idOf(new VmId("utm", "A.utm"), "2026-10-20_1200");
        when(repo.findAll()).thenReturn(Map.of(id, new StableRepository.Meta(true, null)));

        assertThrows(IllegalStateException.class, () -> backupService.delete(id));

        assertTrue(Files.isDirectory(batch.resolve("VMs").resolve("utm__A")));
        assertEquals(1, manifestOf(batch).vms().size());
    }

    @Test
    void deletionIsRefusedWhenFormatTwoBatchExistsButTheMarkerIsMissing() throws IOException {
        // A format-2 batch without a marker is the inconsistent state; nothing may be removed.
        Path batch = batch("2026-10-20_1200", 20, entry("utm", "A.utm", "utm__A"));
        String id = idOf(new VmId("utm", "A.utm"), "2026-10-20_1200");

        assertThrows(BackupStorageReadOnlyException.class, () -> backupService.delete(id));

        assertTrue(Files.isDirectory(batch.resolve("VMs").resolve("utm__A")));
        verifyNoInteractions(repo);
    }

    // ------------------------------------------------------------------
    // Retention
    // ------------------------------------------------------------------

    @Test
    void retentionDeletesOnlyTheSelectedVmContainerAndKeepsTheOtherProvidersVm() throws IOException {
        marker();
        when(settings.getRetentionLimit()).thenReturn(1);
        Path day1 = batch("2026-10-01_1200", 1, entry("utm", "A.utm", "utm__A"), entry("qemu", "A.utm", "qemu__A"));
        Path day2 = batch("2026-10-02_1200", 2, entry("utm", "A.utm", "utm__A"), entry("qemu", "A.utm", "qemu__A"));
        Path day3 = batch("2026-10-03_1200", 3, entry("utm", "A.utm", "utm__A"), entry("qemu", "A.utm", "qemu__A"));

        retentionService.deleteBackupsExceedingRetention(List.of(new VmId("utm", "A.utm")));

        assertFalse(Files.exists(day1.resolve("VMs").resolve("utm__A")));
        assertFalse(Files.exists(day2.resolve("VMs").resolve("utm__A")));
        assertTrue(Files.isDirectory(day3.resolve("VMs").resolve("utm__A")));
        for (Path batch : List.of(day1, day2, day3)) {
            assertTrue(Files.isDirectory(batch.resolve("VMs").resolve("qemu__A")), batch.toString());
            assertTrue(manifestOf(batch).vms().stream()
                    .anyMatch(entry -> entry.id().equals(new VmId("qemu", "A.utm"))), batch.toString());
        }
        assertEquals(List.of(new VmId("qemu", "A.utm")),
                manifestOf(day1).vms().stream().map(BackupManifest.VmEntry::id).toList());
        assertEquals(2, manifestOf(day3).vms().size());
    }

    @Test
    void stableBackupIsNeverDeletedByRetention() throws IOException {
        marker();
        when(settings.getRetentionLimit()).thenReturn(1);
        batch("2026-10-01_1200", 1, entry("utm", "A.utm", "utm__A"));
        batch("2026-10-02_1200", 2, entry("utm", "A.utm", "utm__A"));
        batch("2026-10-03_1200", 3, entry("utm", "A.utm", "utm__A"));
        String oldestStable = idOf(new VmId("utm", "A.utm"), "2026-10-01_1200");
        when(repo.findAll()).thenReturn(Map.of(oldestStable, new StableRepository.Meta(true, null)));

        retentionService.deleteBackupsExceedingRetention(List.of(new VmId("utm", "A.utm")));

        VmOverview vm = find(new VmId("utm", "A.utm"));
        assertEquals(2, vm.backupCount(), "the Stable backup and the newest one remain");
        assertTrue(vm.backups().stream().anyMatch(b -> b.id().equals(oldestStable)));
    }

    @Test
    void retentionCandidateRemovalKeepsManifestsConsistent() throws IOException {
        marker();
        when(settings.getRetentionLimit()).thenReturn(1);
        Path day1 = batch("2026-10-01_1200", 1, entry("utm", "A.utm", "utm__A"), entry("utm", "B.utm", "utm__B"));
        batch("2026-10-02_1200", 2, entry("utm", "A.utm", "utm__A"));

        retentionService.deleteBackupsExceedingRetention(List.of(new VmId("utm", "A.utm")));

        assertEquals(List.of(new VmId("utm", "B.utm")),
                manifestOf(day1).vms().stream().map(BackupManifest.VmEntry::id).toList());
        assertTrue(Files.isDirectory(day1.resolve("VMs").resolve("utm__B")));
        assertTrue(find(new VmId("utm", "A.utm")).backups().stream()
                .noneMatch(b -> b.backupDirectoryName().equals("2026-10-01_1200")));
    }
}
