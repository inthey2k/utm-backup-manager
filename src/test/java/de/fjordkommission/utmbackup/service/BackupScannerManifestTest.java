package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupBatchIssue;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How the scanner treats batches by manifest outcome. Only format-1 batches are
 * shown; format 2, newer, malformed and unreadable manifests are reported and
 * contribute nothing, so their folders are never read as UTM VMs.
 */
class BackupScannerManifestTest {

    private static final String LEGACY_FOLDER = "Linux.utm";

    @TempDir
    Path tempDir;

    private Path root;
    private VmChangeStatusService changeStatusService;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tempDir.resolve("backups"));
        changeStatusService = mock(VmChangeStatusService.class);
        when(changeStatusService.determine(anyString(), any(), anyBoolean()))
                .thenReturn(VmChangeStatus.UNKNOWN);
    }

    @Test
    void formatOneBatchWithFormatTwoLookingCommentStaysVisible() throws IOException {
        createBatch("2026-09-01_1200", "Comment:\nFormat-Version: 2\n", LEGACY_FOLDER);

        BackupOverview overview = scanner().scan();

        VmOverview linux = find(overview, new VmId("utm", LEGACY_FOLDER));
        assertEquals(1, linux.backupCount());
        assertEquals("Format-Version: 2", linux.backups().getFirst().comment());
        assertTrue(overview.batchIssues().isEmpty());
    }

    @Test
    void formatTwoBatchIsNotShownAndIsReportedInsteadOfBeingReadAsUtmFolders() throws IOException {
        createBatch("2026-10-20_1200", formatTwoManifest("utm__ArchLinux-02.27"), "utm__ArchLinux-02.27");
        createBatch("2026-09-01_1200", "Comment:\nok\n", LEGACY_FOLDER);

        BackupOverview overview = scanner().scan();

        assertTrue(overview.vms().stream().noneMatch(vm -> vm.id().localId().equals("utm__ArchLinux-02.27")));
        assertEquals(1, find(overview, new VmId("utm", LEGACY_FOLDER)).backupCount());
        assertIssue(overview, "2026-10-20_1200", "Format 2 backups are not shown");
    }

    @Test
    void newerFormatIsReportedExplicitly() throws IOException {
        createBatch("2026-10-20_1200", "Format-Version: 3\nComment:\nfuture\n", "Future.utm");

        BackupOverview overview = scanner().scan();

        assertIssue(overview, "2026-10-20_1200", "newer than the supported format 2");
        assertTrue(overview.vms().stream().noneMatch(vm -> vm.id().localId().equals("Future.utm")));
    }

    @Test
    void invalidFormatTwoIsNeverDowngradedToFormatOne() throws IOException {
        // Two VMs whose container directories differ only in case: ambiguous, so invalid.
        String duplicateDirectory = String.join("\n",
                "Format-Version: 2",
                "Time: 2026-10-20T12:00:00+02:00",
                "",
                "VM:",
                "Provider: utm",
                "VM-ID: A.utm",
                "Directory: utm__same",
                "",
                "VM:",
                "Provider: qemu",
                "VM-ID: A",
                "Directory: UTM__SAME",
                "",
                "Comment:",
                "ambiguous",
                "");
        createBatch("2026-10-20_1200", duplicateDirectory, "utm__same");

        BackupOverview overview = scanner().scan();

        assertNoBackupsShown(overview);
        assertIssue(overview, "2026-10-20_1200", "Duplicate container directory");
    }

    @Test
    void malformedFormatVersionIsReportedAndFolderIsNotRead() throws IOException {
        createBatch("2026-10-20_1200", "Format-Version: two\nComment:\nx\n", LEGACY_FOLDER);

        BackupOverview overview = scanner().scan();

        assertNoBackupsShown(overview);
        assertIssue(overview, "2026-10-20_1200", "Format-Version is not a number");
    }

    @Test
    void unreadableManifestIsReportedAndNotTreatedAsLegacy() throws IOException {
        Path batch = createBatch("2026-09-01_1200", "Comment:\nsecret\n", LEGACY_FOLDER);
        Path manifest = batch.resolve("BACKUP-INFO.txt");
        Files.setPosixFilePermissions(manifest, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(manifest), "unreadable file requires a non-root user");

        BackupOverview overview = scanner().scan();

        assertNoBackupsShown(overview);
        assertIssue(overview, "2026-09-01_1200", "could not be read");
    }

    private BackupScanner scanner() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(new LocalVm(LEGACY_FOLDER, "Linux", List.of(Path.of("/vms", LEGACY_FOLDER)))),
                Map.of(LEGACY_FOLDER, VmStatus.STOPPED)
        );

        VmProviderRegistry registry = new VmProviderRegistry(List.of(utm), new VmProperties(List.of("utm"), null, null));

        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);

        return new BackupScanner(
                new BackupProperties(root, tempDir.resolve("metadata.db"), tempDir.resolve("state.tsv")),
                mock(StableRepository.class),
                settings,
                new VmInventory(registry),
                changeStatusService
        );
    }

    private Path createBatch(String directoryName, String manifest, String vmFolder) throws IOException {
        Path batch = root.resolve(directoryName);

        Files.createDirectories(batch.resolve("VMs").resolve(vmFolder));
        Files.writeString(batch.resolve("BACKUP-INFO.txt"), manifest);

        return batch;
    }

    private static String formatTwoManifest(String directory) {
        return String.join("\n",
                "Backup created by UTM Backup Manager",
                "Format-Version: 2",
                "Time: 2026-10-20T12:00:00+02:00",
                "",
                "VM:",
                "Provider: utm",
                "VM-ID: ArchLinux 02.27.utm",
                "Name: ArchLinux 02.27",
                "Directory: " + directory,
                "",
                "Comment:",
                "two-format batch",
                "");
    }

    private static VmOverview find(BackupOverview overview, VmId id) {
        return overview.vms().stream()
                .filter(vm -> vm.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry for " + id));
    }

    /**
     * The configured local VM is always listed, so the check is that no backup is
     * shown and no VM entry was created from a rejected batch.
     */
    private static void assertNoBackupsShown(BackupOverview overview) {
        assertTrue(overview.vms().stream().allMatch(vm -> vm.backups().isEmpty()), "no backup may be shown");
        assertTrue(overview.vms().stream().noneMatch(vm -> vm.id().localId().startsWith("utm__")));
    }

    private static void assertIssue(BackupOverview overview, String batchName, String reasonFragment) {
        BackupBatchIssue issue = overview.batchIssues().stream()
                .filter(candidate -> candidate.batchDirectoryName().equals(batchName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No batch issue for " + batchName));

        assertTrue(issue.reason().contains(reasonFragment), issue.reason());
    }
}
