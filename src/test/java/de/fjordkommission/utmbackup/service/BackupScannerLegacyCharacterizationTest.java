package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for the format-1 (legacy) backup layout as implemented
 * in 1.4.x: {@code <batch>/VMs/<folder>/} with a {@code BACKUP-INFO.txt} manifest.
 * The backup id expectations are persisted in SQLite (stable flags, notes), so
 * they must never change. Tests whose names contain "characterizes" document
 * current behaviour that is expected to be revisited later; they are not a
 * statement of the desired long-term behaviour.
 */
class BackupScannerLegacyCharacterizationTest {

    private static final String LINUX_FOLDER = "Linux.utm";

    /** base64url("2026-09-01_1200" + "\n" + "Linux.utm"), as produced in 1.4.0. */
    private static final String GOLDEN_ID_LINUX =
            "MjAyNi0wOS0wMV8xMjAwCkxpbnV4LnV0bQ";

    /** base64url("2026-09-02_1200_stable" + "\n" + "Linux.utm"), as produced in 1.4.0. */
    private static final String GOLDEN_ID_LINUX_STABLE_DIR =
            "MjAyNi0wOS0wMl8xMjAwX3N0YWJsZQpMaW51eC51dG0";

    /** Manifest layout written by CreateBackupService in 1.4.0 (with VMs section). */
    private static final String BACKUP_INFO_WITH_VMS = """
            Backup created by VM Backup Manager
            Time: 2026-09-01T12:00:00
            VMs:
            test-linux-vm

            Comment:
            Before kernel update
            """;

    @TempDir
    Path tempDir;

    private Path root;
    private StableRepository stableRepository;
    private VmProvider provider;
    private BackupScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("backups");
        Files.createDirectories(root);

        BackupProperties properties = new BackupProperties(
                root,
                tempDir.resolve("metadata.db"),
                tempDir.resolve("backup-state.tsv")
        );

        stableRepository = mock(StableRepository.class);

        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);

        VmChangeStatusService changeStatusService = mock(VmChangeStatusService.class);
        when(changeStatusService.determine(any(VmId.class), any(), anyBoolean()))
                .thenReturn(VmChangeStatus.UNKNOWN);

        provider = mock(VmProvider.class);
        when(provider.providerId()).thenReturn("utm");
        when(provider.displayName()).thenReturn("UTM");
        when(provider.findAll()).thenReturn(List.of(
                new LocalVm(
                        LINUX_FOLDER,
                        "Linux",
                        List.of(tempDir.resolve("source").resolve(LINUX_FOLDER))
                )
        ));
        when(provider.findStatuses()).thenReturn(Map.of(LINUX_FOLDER, VmStatus.STOPPED));

        VmProviderRegistry registry = mock(VmProviderRegistry.class);
        when(registry.enabledProviders()).thenReturn(List.of(provider));

        scanner = new BackupScanner(
                properties,
                stableRepository,
                settings,
                new VmInventory(registry),
                changeStatusService,
                mock(BackupStorageGuard.class)
        );
    }

    // ------------------------------------------------------------------
    // Backup ids
    // ------------------------------------------------------------------

    @Test
    void backupIdOfLegacyBatchIsByteIdenticalToVersion140() throws IOException {
        createBatch("2026-09-01_1200", null);

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals(GOLDEN_ID_LINUX, backup.id());
        assertEquals("2026-09-01_1200", backup.backupDirectoryName());
        assertEquals(LINUX_FOLDER, backup.vmName());
    }

    @Test
    void vmIdIsTheLegacyPackageFolderNameAndMatchesTheLocalVm() throws IOException {
        createBatch("2026-09-01_1200", null);

        VmOverview vm = onlyVm(scanner.scan());

        assertEquals(new VmId("utm", LINUX_FOLDER), vm.id());
        assertEquals("utm", vm.providerId());
        assertEquals("UTM", vm.providerName());
        assertEquals("Linux", vm.name());
        assertTrue(vm.local());
        assertEquals(VmStatus.STOPPED, vm.status());
    }

    @Test
    void statusIsLookedUpByLocalIdAndNotByDisplayName() throws IOException {
        // Phase 1: provider statuses are keyed by the local id. A display-name key is ignored.
        when(provider.findStatuses()).thenReturn(Map.of("Linux", VmStatus.STOPPED));
        createBatch("2026-09-01_1200", null);

        VmOverview vm = onlyVm(scanner.scan());

        assertEquals(VmStatus.UNKNOWN, vm.status());
    }

    // ------------------------------------------------------------------
    // Legacy *_stable directories
    // ------------------------------------------------------------------

    @Test
    void legacyStableDirectoryIsRecognisedAndPersistedAsStable() throws IOException {
        createBatch("2026-09-02_1200_stable", null);

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals(GOLDEN_ID_LINUX_STABLE_DIR, backup.id());
        assertTrue(backup.stable());
        verify(stableRepository).save(GOLDEN_ID_LINUX_STABLE_DIR, true, null);
    }

    @Test
    void storedMetadataOverridesLegacyStableDirectoryMarker() throws IOException {
        createBatch("2026-09-02_1200_stable", null);
        when(stableRepository.findAll()).thenReturn(Map.of(
                GOLDEN_ID_LINUX_STABLE_DIR,
                new StableRepository.Meta(false, "unpinned")
        ));

        VmBackup backup = onlyBackup(scanner.scan());

        // Characterizes: once a metadata row exists, the directory name is ignored.
        assertFalse(backup.stable());
        assertEquals("unpinned", backup.note());
        verify(stableRepository, never()).save(anyString(), anyBoolean(), any());
    }

    @Test
    void storedStableMetadataAppliesToNormalDirectoryName() throws IOException {
        createBatch("2026-09-01_1200", null);
        when(stableRepository.findAll()).thenReturn(Map.of(
                GOLDEN_ID_LINUX,
                new StableRepository.Meta(true, "keep this")
        ));

        VmBackup backup = onlyBackup(scanner.scan());

        assertTrue(backup.stable());
        assertEquals("keep this", backup.note());
    }

    // ------------------------------------------------------------------
    // BACKUP-INFO.txt: comment and time
    // ------------------------------------------------------------------

    @Test
    void commentIsReadAfterLegacyVmsSection() throws IOException {
        createBatch("2026-09-01_1200", BACKUP_INFO_WITH_VMS);

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals("Before kernel update", backup.comment());
    }

    static Stream<Arguments> commentExtractionCases() {
        return Stream.of(
                Arguments.of("""
                        Backup created by VM Backup Manager
                        Time: 2026-09-01T12:00:00

                        Comment:
                        No VMs section here
                        """, "No VMs section here"),
                Arguments.of("""
                        Backup created by VM Backup Manager
                        Comment:
                          first line
                        second line

                        """, "first line\nsecond line"),
                Arguments.of("""
                        Backup created by VM Backup Manager
                        Comment:
                        VMs:
                        not a VM list
                        """, "VMs:\nnot a VM list")
        );
    }

    /**
     * Everything after the Comment marker is the comment. Outer whitespace is trimmed,
     * and comment text that resembles a manifest section is kept verbatim.
     */
    @ParameterizedTest
    @MethodSource("commentExtractionCases")
    void commentIsReadVerbatimAfterTheCommentMarker(String backupInfo, String expectedComment) throws IOException {
        createBatch("2026-09-01_1200", backupInfo);

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals(expectedComment, backup.comment());
    }

    @Test
    void missingCommentMarkerYieldsEmptyComment() throws IOException {
        createBatch("2026-09-01_1200", "Test backup metadata");

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals("", backup.comment());
    }

    @Test
    void missingBackupInfoYieldsEmptyComment() throws IOException {
        createBatch("2026-09-01_1200", null);

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals("", backup.comment());
    }

    @Test
    void backupTimeComesFromBackupInfoModificationTimeWhenPresent() throws IOException {
        // Characterizes the ordering basis used by retention: the file mtime,
        // not the "Time:" line and not the directory name.
        Path batch = createBatch("2026-09-01_1200", BACKUP_INFO_WITH_VMS);
        LocalDateTime modified = LocalDateTime.of(2026, 3, 4, 5, 6, 7);
        Files.setLastModifiedTime(
                batch.resolve("BACKUP-INFO.txt"),
                FileTime.from(modified.atZone(ZoneId.systemDefault()).toInstant())
        );

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals(modified, backup.backupTime());
    }

    @Test
    void backupTimeFallsBackToDirectoryNameWithTime() throws IOException {
        createBatch("2026-09-01_1200", null);

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals(LocalDateTime.of(2026, 9, 1, 12, 0), backup.backupTime());
    }

    @Test
    void backupTimeFallsBackToStartOfDayForDateOnlyDirectoryName() throws IOException {
        createBatch("2026-09-01", null);

        VmBackup backup = onlyBackup(scanner.scan());

        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), backup.backupTime());
    }

    // ------------------------------------------------------------------
    // Layout assumptions and attribution
    // ------------------------------------------------------------------

    @Test
    void filesDirectlyBelowVmsAreIgnoredByTheScanner() throws IOException {
        // Characterizes the "one directory per VM" assumption: a file source
        // is silently invisible in the overview.
        Path batch = createBatch("2026-09-01_1200", null);
        Files.writeString(batch.resolve("VMs").resolve("edk2-code.fd"), "firmware");

        BackupOverview overview = scanner.scan();

        assertEquals(
                List.of(new VmId("utm", LINUX_FOLDER)),
                overview.vms().stream().map(VmOverview::id).toList()
        );
    }

    @Test
    void batchesWithoutDatePrefixAreIgnored() throws IOException {
        createBatch(".incomplete-2026-09-05_1200", null);

        // The configured local VM is always listed, but it must have no backups.
        BackupOverview overview = scanner.scan();

        assertTrue(backups(overview).isEmpty());
    }

    @Test
    void backupOnlyVmIsListedUnderItsFolderNameWithUnknownStatus() throws IOException {
        Files.createDirectories(root.resolve("2026-09-01_1200").resolve("VMs").resolve("Old.utm"));

        // Overview contains the configured local VM (Linux.utm) plus the backup-only entry.
        BackupOverview overview = scanner.scan();
        assertEquals(2, overview.vms().size());
        VmOverview vm = overview.vms().stream()
                .filter(candidate -> candidate.id().localId().equals("Old.utm"))
                .findFirst()
                .orElseThrow();

        assertEquals(new VmId("utm", "Old.utm"), vm.id());
        assertEquals("Old.utm", vm.name());
        assertFalse(vm.local());
        assertEquals(VmStatus.UNKNOWN, vm.status());
    }

    @Test
    void vmFoldersDifferingOnlyInCaseAreSeparateVms() throws IOException {
        // Phase 3: identities are exact VmIds. Folder names that differ only in case
        // are distinct VMs. Phase 0 recorded the former merge as a defect.
        createBatch("2026-09-01_1200", null);
        Files.createDirectories(root.resolve("2026-09-02_1200").resolve("VMs").resolve("linux.utm"));

        BackupOverview overview = scanner.scan();

        assertEquals(2, overview.vms().size());
        assertEquals(1, overview.vms().get(0).backupCount());
        assertEquals(1, overview.vms().get(1).backupCount());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Path createBatch(String directoryName, String backupInfo) throws IOException {
        Path batch = root.resolve(directoryName);

        Files.createDirectories(batch.resolve("VMs").resolve(LINUX_FOLDER));

        if (backupInfo != null) {
            Files.writeString(batch.resolve("BACKUP-INFO.txt"), backupInfo);
        }

        return batch;
    }

    private static List<VmBackup> backups(BackupOverview overview) {
        return overview.vms().stream()
                .flatMap(vm -> vm.backups().stream())
                .toList();
    }

    private static VmBackup onlyBackup(BackupOverview overview) {
        List<VmBackup> backups = backups(overview);

        assertEquals(1, backups.size(), "expected exactly one backup");
        return backups.getFirst();
    }

    private static VmOverview onlyVm(BackupOverview overview) {
        assertEquals(1, overview.vms().size(), "expected exactly one VM");
        return overview.vms().getFirst();
    }
}
