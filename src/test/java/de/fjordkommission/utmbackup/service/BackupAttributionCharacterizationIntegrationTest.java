package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.AbstractIntegrationTest;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: a backup of a local VM whose folder name differs from its id is
 * attributed to that VM, because format-2 backups carry the canonical identity.
 */
@SpringBootTest
@ActiveProfiles("test")
class BackupAttributionCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String LOCAL_VM_ID = "test-linux-vm";
    private static final String LOCAL_VM_NAME = "Test Linux VM";
    private static final String SOURCE_FOLDER = "TestLinux.utm";

    @Autowired
    private VmInventory vmInventory;

    @Autowired
    private CreateBackupService createBackupService;

    @Autowired
    private BackupScanner scanner;

    @BeforeEach
    void prepare() throws IOException {
        if (Files.exists(TEST_BACKUP_ROOT)) {
            try (var paths = Files.walk(TEST_BACKUP_ROOT)) {
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

        Files.createDirectories(TEST_BACKUP_ROOT);

        Path source = TEST_ROOT.resolve("vms").resolve(SOURCE_FOLDER);
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.plist"), "attribution test");
    }

    /**
     * Formerly a characterization of the attribution gap. Format-2 backups record the
     * canonical VmId in the manifest, so the backup now belongs to the local VM and no
     * separate backup-only entry appears.
     */
    @Test
    void backupOfTestProviderVmIsAttributedToTheLocalVm() {
        LocalVm localVm = vmInventory.build().vms().stream()
                .filter(vm -> vm.id().equals(new VmId("utm", LOCAL_VM_ID)))
                .findFirst()
                .orElseThrow()
                .localVm();

        createBackupService.create(List.of(localVm), "Attribution characterization");

        BackupOverview overview = scanner.scan();

        VmOverview local = findVmByName(overview, LOCAL_VM_NAME);
        assertTrue(local.local());
        assertEquals(new VmId("utm", LOCAL_VM_ID), local.id());
        assertEquals(VmStatus.STOPPED, local.status());
        assertEquals(1, local.backupCount());

        assertTrue(overview.vms().stream().noneMatch(vm -> vm.name().equals(SOURCE_FOLDER)),
                "no backup-only entry for the source folder");
    }

    private static VmOverview findVmByName(BackupOverview overview, String name) {
        return overview.vms().stream()
                .filter(vm -> vm.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry named " + name));
    }
}
