package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.repository.StableRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BackupScannerIntegrationTest {

    private static final Path TEST_ROOT = createTestRoot();
    private static final Path BACKUP_ROOT = TEST_ROOT.resolve("backups");
    private static final Path DATABASE = TEST_ROOT.resolve("backup-manager.db");

    @Autowired
    private BackupScanner scanner;

    @Autowired
    private BackupService backupService;

    @Autowired
    private StableRepository stableRepository;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("backup.root", BACKUP_ROOT::toString);
        registry.add("backup.metadata-db", DATABASE::toString);
        registry.add("vm.provider", () -> "test");
    }

    @BeforeEach
    void cleanBackups() throws IOException {
        if (Files.exists(BACKUP_ROOT)) {
            try (var paths = Files.walk(BACKUP_ROOT)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }

        Files.createDirectories(BACKUP_ROOT);
    }

    @Test
    void oldestBackupBecomesRetentionCandidate() throws IOException {
        createBackup("2026-09-01_1200");
        createBackup("2026-09-02_1200");
        createBackup("2026-09-03_1200");
        createBackup("2026-09-04_1200");

        BackupOverview overview = scanner.scan();

        VmOverview vm = findTestVm(overview);

        assertEquals(4, vm.backupCount());
        assertEquals(4, vm.normalCount());
        assertEquals(0, vm.stableCount());

        assertTrue(overview.retentionExceeded().contains("test-linux-vm"));

        VmBackup oldest = findBackup(vm, "2026-09-01_1200");

        assertTrue(
                overview.retentionCandidatesForDeletion().contains(oldest.id())
        );
    }

    @Test
    void stableBackupIsExcludedFromRetention() throws IOException {
        createBackup("2026-09-01_1200");
        createBackup("2026-09-02_1200");
        createBackup("2026-09-03_1200");
        createBackup("2026-09-04_1200");

        BackupOverview initialOverview = scanner.scan();
        VmOverview initialVm = findTestVm(initialOverview);

        VmBackup oldest = findBackup(initialVm, "2026-09-01_1200");

        stableRepository.save(oldest.id(), true, null);

        BackupOverview overview = scanner.scan();
        VmOverview vm = findTestVm(overview);

        assertEquals(4, vm.backupCount());
        assertEquals(3, vm.normalCount());
        assertEquals(1, vm.stableCount());

        assertFalse(overview.retentionExceeded().contains("test-linux-vm"));
        assertFalse(
                overview.retentionCandidatesForDeletion().contains(oldest.id())
        );
    }

    @Test
    void normalBackupCanBeDeleted() throws IOException {
        Path vmPath = createDeletableBackup("2026-09-10_1200");

        BackupOverview overview = scanner.scan();
        VmOverview vm = findVm(overview, "Deletable Test VM");
        VmBackup backup = findBackup(vm, "2026-09-10_1200");

        assertTrue(Files.isDirectory(vmPath));

        backupService.delete(backup.id());

        assertFalse(Files.exists(vmPath));
    }

    @Test
    void stableBackupCannotBeDeleted() throws IOException {
        Path vmPath = createDeletableBackup("2026-09-11_1200");

        BackupOverview initialOverview = scanner.scan();
        VmOverview initialVm = findVm(initialOverview, "Deletable Test VM");
        VmBackup backup = findBackup(initialVm, "2026-09-11_1200");

        stableRepository.save(backup.id(), true, null);

        BackupOverview stableOverview = scanner.scan();
        VmBackup stableBackup = findBackup(
                findVm(stableOverview, "Deletable Test VM"),
                "2026-09-11_1200"
        );

        assertTrue(stableBackup.stable());

        assertThrows(
                IllegalStateException.class,
                () -> backupService.delete(stableBackup.id())
        );

        assertTrue(Files.isDirectory(vmPath));
    }

    @Test
    void missingBackupRootIsReportedAsUnavailable() throws IOException {
        Files.delete(BACKUP_ROOT);

        BackupRootUnavailableException exception = assertThrows(
                BackupRootUnavailableException.class,
                scanner::scan
        );

        assertEquals(BACKUP_ROOT, exception.root());
    }

    @Test
    void deletingOneBackupFromBatchKeepsOtherBackup() throws IOException {
        String directoryName = "2026-09-30_1200";

        Path batchDirectory = BACKUP_ROOT.resolve(directoryName);
        Path vmDirectory = batchDirectory.resolve("VMs");

        Path firstVmPath = vmDirectory.resolve("deletable-test-vm");
        Path secondVmPath = vmDirectory.resolve("second-deletable-test-vm");

        Files.createDirectories(firstVmPath);
        Files.createDirectories(secondVmPath);

        Files.writeString(
                firstVmPath.resolve("test.txt"),
                "This VM backup must be deleted."
        );

        Files.writeString(
                secondVmPath.resolve("test.txt"),
                "This VM backup must remain."
        );

        BackupOverview overview = scanner.scan();

        VmBackup backupToDelete = findBackup(
                findVm(overview, "Deletable Test VM"),
                directoryName
        );

        backupService.delete(backupToDelete.id());

        assertFalse(Files.exists(firstVmPath));

        assertTrue(Files.isDirectory(secondVmPath));
        assertTrue(Files.exists(secondVmPath.resolve("test.txt")));
        assertTrue(Files.isDirectory(batchDirectory));

        BackupOverview afterDeletion = scanner.scan();

        VmBackup remainingBackup = findBackup(
                findVm(afterDeletion, "Second Deletable Test VM"),
                directoryName
        );

        assertEquals(secondVmPath, remainingBackup.vmPath());
    }

    @Test
    void deletingLastBackupRemovesEmptyBatchDirectory() throws IOException {
        String directoryName = "2026-09-30_1300";

        Path batchDirectory = BACKUP_ROOT.resolve(directoryName);
        Path vmDirectory = batchDirectory.resolve("VMs");
        Path vmPath = vmDirectory.resolve("deletable-test-vm");
        Path backupInfo = batchDirectory.resolve("BACKUP-INFO.txt");

        Files.createDirectories(vmPath);

        Files.writeString(
                vmPath.resolve("test.txt"),
                "This VM backup must be deleted."
        );

        Files.writeString(
                backupInfo,
                "Test backup metadata"
        );

        BackupOverview overview = scanner.scan();

        VmBackup backup = findBackup(
                findVm(overview, "Deletable Test VM"),
                directoryName
        );

        backupService.delete(backup.id());

        assertFalse(Files.exists(vmPath));
        assertFalse(Files.exists(batchDirectory));
    }

    @Test
    void backupsFromSameBatchHaveDifferentIds() throws IOException {
        String directoryName = "2026-09-30_1400";

        Path vmDirectory = BACKUP_ROOT
                .resolve(directoryName)
                .resolve("VMs");

        Files.createDirectories(
                vmDirectory.resolve("deletable-test-vm")
        );

        Files.createDirectories(
                vmDirectory.resolve("second-deletable-test-vm")
        );

        BackupOverview overview = scanner.scan();

        VmBackup firstBackup = findBackup(
                findVm(overview, "Deletable Test VM"),
                directoryName
        );

        VmBackup secondBackup = findBackup(
                findVm(overview, "Second Deletable Test VM"),
                directoryName
        );

        assertNotEquals(firstBackup.id(), secondBackup.id());
    }



    /**
     * FROM HERE ON DOWNWARD ONLY HELPER METHODS
     */

    private void createBackup(String directoryName) throws IOException {
        Files.createDirectories(
                BACKUP_ROOT
                        .resolve(directoryName)
                        .resolve("VMs")
                        .resolve("test-linux-vm")
        );
    }

    private VmOverview findTestVm(BackupOverview overview) {
        return findVm(overview, "Test Linux VM");
    }

    private VmBackup findBackup(VmOverview vm, String directoryName) {
        return vm.backups().stream()
                .filter(backup ->
                        backup.backupDirectoryName().equals(directoryName))
                .findFirst()
                .orElseThrow();
    }

    private static Path createTestRoot() {
        try {
            return Files.createTempDirectory(
                    "utm-backup-manager-scanner-test-"
            );
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Path createDeletableBackup(String directoryName) throws IOException {
        Path vmPath = BACKUP_ROOT
                .resolve(directoryName)
                .resolve("VMs")
                .resolve("deletable-test-vm");

        Files.createDirectories(vmPath);

        Files.writeString(
                vmPath.resolve("test.txt"),
                "This file must be deleted together with the VM backup."
        );

        return vmPath;
    }

    private VmOverview findVm(BackupOverview overview, String vmName) {
        return overview.vms().stream()
                .filter(vm -> vm.name().equals(vmName))
                .findFirst()
                .orElseThrow();
    }
}