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
 * Characterization test for the backup attribution gap.
 *
 * {@link TestVmProvider} exposes the local VM with id {@code test-linux-vm} but
 * its backup source is {@code TestLinux.utm}. The backup is stored in a folder
 * named after the source file, and the scanner identifies backups by that
 * folder. Consequently the backup is not attributed to the local VM.
 *
 * The assertions below describe the CURRENT (incorrect) behaviour so the gap is
 * explicit. They must be inverted when backup identity is fixed.
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

    @Test
    void backupOfTestProviderVmIsListedUnderSourceFolderNotLocalVm() {
        LocalVm localVm = vmInventory.build().vms().stream()
                .filter(vm -> vm.id().equals(new VmId("utm", LOCAL_VM_ID)))
                .findFirst()
                .orElseThrow()
                .localVm();

        createBackupService.create(List.of(localVm), "Attribution characterization");

        BackupOverview overview = scanner.scan();

        // Current behaviour: the local VM shows no backups ...
        VmOverview local = findVmByName(overview, LOCAL_VM_NAME);
        assertTrue(local.local());
        assertEquals(new VmId("utm", LOCAL_VM_ID), local.id());
        assertEquals(VmStatus.STOPPED, local.status());
        assertEquals(0, local.backupCount());

        // ... while the backup appears as a separate backup-only entry whose
        // identity is the source folder name.
        VmOverview backupOnly = findVmByName(overview, SOURCE_FOLDER);
        assertFalse(backupOnly.local());
        assertEquals(new VmId("utm", SOURCE_FOLDER), backupOnly.id());
        assertEquals(VmStatus.UNKNOWN, backupOnly.status());
        assertEquals(1, backupOnly.backupCount());
    }

    private static VmOverview findVmByName(BackupOverview overview, String name) {
        return overview.vms().stream()
                .filter(vm -> vm.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry named " + name));
    }
}
