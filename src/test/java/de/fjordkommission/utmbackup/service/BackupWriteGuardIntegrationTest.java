package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.BackupStorageMetadataReader;
import de.fjordkommission.utmbackup.storage.BackupStorageReadOnlyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Each mutating operation is refused on a read-only medium before it touches the
 * file system or SQLite. Read paths keep working, and a pure legacy medium behaves
 * as before.
 */
class BackupWriteGuardIntegrationTest {

    private static final String BATCH = "2026-09-01_1200";
    private static final String NEWER_MARKER = "format.version=3\ncreated.at=2026-10-21T12:00:00+02:00\n";

    @TempDir
    Path root;

    private StableRepository repo;
    private BackupScanner scanner;
    private BackupStorageGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve(BATCH).resolve("VMs").resolve("Linux.utm"));
        repo = mock(StableRepository.class);
        guard = new BackupStorageGuard(new BackupProperties(root, null, null));
        scanner = realScanner();
    }

    @Test
    void deleteIsRefusedBeforeAnyFileIsRemovedOrMetadataChanged() throws IOException {
        marker(NEWER_MARKER);
        BackupScanner mockedScanner = mock(BackupScanner.class);
        BackupService service = new BackupService(mockedScanner, repo, guard);

        assertThrows(BackupStorageReadOnlyException.class, () -> service.delete("any-id"));

        assertTrue(Files.isDirectory(root.resolve(BATCH).resolve("VMs").resolve("Linux.utm")));
        verifyNoInteractions(mockedScanner, repo);
    }

    @Test
    void stableChangeIsRefusedBeforeSqliteIsChanged() throws IOException {
        marker("invalid");
        BackupScanner mockedScanner = mock(BackupScanner.class);
        BackupService service = new BackupService(mockedScanner, repo, guard);

        assertThrows(BackupStorageReadOnlyException.class, () -> service.stable("any-id", true));

        verifyNoInteractions(mockedScanner, repo);
    }

    @Test
    void noteChangeIsRefusedBeforeSqliteIsChanged() throws IOException {
        marker(NEWER_MARKER);
        BackupScanner mockedScanner = mock(BackupScanner.class);
        BackupService service = new BackupService(mockedScanner, repo, guard);

        assertThrows(BackupStorageReadOnlyException.class, () -> service.note("any-id", "text"));

        verifyNoInteractions(mockedScanner, repo);
    }

    @Test
    void createBackupIsRefusedWithoutCreatingAnyDirectoryOrClearingState() throws IOException {
        marker(NEWER_MARKER);
        Path source = Files.createDirectories(root.resolve("source").resolve("Linux.utm"));
        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        CreateBackupService create = new CreateBackupService(
                new BackupProperties(root, null, null), changeStatus, guard, mock(org.springframework.boot.info.BuildProperties.class));
        List<String> before = entries();

        List<LocalVm> vms = List.of(new LocalVm("Linux.utm", "Linux", List.of(source)));

        assertThrows(BackupStorageReadOnlyException.class, () -> create.create(vms, "comment"));

        assertEquals(before, entries());
        verifyNoInteractions(changeStatus);
    }

    @Test
    void retentionCleanupIsRefusedBeforeAnyDeletion() throws IOException {
        marker("invalid");
        BackupScanner mockedScanner = mock(BackupScanner.class);
        BackupService backupService = mock(BackupService.class);
        RetentionService retention = new RetentionService(mockedScanner, backupService, guard);

        List<VmId> ids = List.of(new VmId("utm", "Linux.utm"));

        assertThrows(BackupStorageReadOnlyException.class, () -> retention.deleteBackupsExceedingRetention(ids));

        verifyNoInteractions(mockedScanner, backupService);
    }

    @Test
    void backupJobIsRefusedBeforeItIsScheduled() throws IOException {
        marker(NEWER_MARKER);
        CreateBackupService create = mock(CreateBackupService.class);
        RetentionService retention = mock(RetentionService.class);
        AtomicInteger scheduled = new AtomicInteger();
        Executor executor = task -> scheduled.incrementAndGet();
        BackupJobService jobs = new BackupJobService(
                create, mock(RetentionCleanupJobService.class), retention, guard, executor);

        List<LocalVm> vms = List.of(new LocalVm("Linux.utm", "Linux", List.of(root)));

        assertThrows(BackupStorageReadOnlyException.class, () -> jobs.start(vms, null));

        assertEquals(0, scheduled.get());
        verifyNoInteractions(create, retention);
    }

    @Test
    void scanningStillWorksOnNewerMediumAndShowsReadableBatches() throws IOException {
        marker(NEWER_MARKER);

        VmOverview linux = find(scanner.scan(), new VmId("utm", "Linux.utm"));

        assertEquals(1, linux.backupCount());
    }

    @Test
    void legacyStableMigrationIsSkippedOnReadOnlyMediumWhileStillShownAsStable() throws IOException {
        Files.createDirectories(root.resolve("2026-09-02_1200_stable").resolve("VMs").resolve("Linux.utm"));
        marker(NEWER_MARKER);

        VmOverview linux = find(scanner.scan(), new VmId("utm", "Linux.utm"));

        assertTrue(linux.backups().stream().anyMatch(VmBackup::stable));
        verify(repo, never()).save(anyString(), anyBoolean(), any());
    }

    @Test
    void legacyStableMigrationIsStillPersistedOnALegacyMedium() throws IOException {
        Files.createDirectories(root.resolve("2026-09-02_1200_stable").resolve("VMs").resolve("Linux.utm"));

        scanner.scan();

        verify(repo).save(anyString(), eq(true), isNull());
    }

    @Test
    void stableChangeIsPersistedOnALegacyMedium() {
        BackupScanner mockedScanner = mock(BackupScanner.class);
        VmBackup backup = new VmBackup("id", BATCH, "Linux.utm", root, null, "", false, null);
        when(mockedScanner.find("id")).thenReturn(Optional.of(backup));
        BackupService service = new BackupService(mockedScanner, repo, guard);

        service.stable("id", true);

        verify(repo).saveStable("id", true);
    }

    private void marker(String text) throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), text);
    }

    private List<String> entries() throws IOException {
        try (Stream<Path> paths = Files.list(root)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static VmOverview find(BackupOverview overview, VmId id) {
        return overview.vms().stream()
                .filter(vm -> vm.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry for " + id));
    }

    private BackupScanner realScanner() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(new LocalVm("Linux.utm", "Linux", List.of(Path.of("/vms", "Linux.utm")))),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );
        VmProviderRegistry registry = new VmProviderRegistry(List.of(utm), new VmProperties(List.of("utm"), null, null));
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);
        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        when(changeStatus.determine(any(VmId.class), any(), anyBoolean())).thenReturn(VmChangeStatus.UNKNOWN);

        return new BackupScanner(
                new BackupProperties(root, null, null),
                repo,
                settings,
                new VmInventory(registry),
                changeStatus,
                guard
        );
    }
}
