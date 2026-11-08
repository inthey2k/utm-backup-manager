package de.fjordkommission.utmbackup.provider.qemu;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.provider.VmIdentitySidecar;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QEMU VMs have no native id, so each one gets a generated id kept in its directory.
 * The tests cover restart, rename, display names, missing or unreadable metadata, and
 * the disk images that must stay unchanged.
 */
class QemuGeneratedIdentityTest {

    @TempDir
    Path tempDir;

    private Path root;

    private static final String DISK = "arch-linux-disk.qcow2";

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tempDir.resolve("VMs"));
    }

    private Path vm(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        Files.write(directory.resolve(DISK), new byte[] {9, 8, 7});
        return directory;
    }

    private static LocalVm only(List<LocalVm> vms) {
        assertEquals(1, vms.size());
        return vms.getFirst();
    }

    @Test
    void aNewVmGetsAGeneratedIdThatIsWrittenIntoItsDirectory() throws IOException {
        Path arch = vm("ArchLinux");

        LocalVm discovered = only(new QemuVmProvider(root, List::of).findAll());

        assertEquals(VmIdentitySidecar.read(arch, "qemu").orElseThrow(), discovered.id());
        assertNotEquals("ArchLinux", discovered.id());
    }

    @Test
    void theSameIdIsFoundAgainAfterARestart() throws IOException {
        vm("ArchLinux");

        String beforeRestart = only(new QemuVmProvider(root, List::of).findAll()).id();
        // A new provider instance stands in for an application restart.
        String afterRestart = only(new QemuVmProvider(root, List::of).findAll()).id();

        assertEquals(beforeRestart, afterRestart);
    }

    @Test
    void theIdSurvivesARenameOfTheDirectoryWhileTheNameFollowsIt() throws IOException {
        Path arch = vm("ArchLinux");
        String id = only(new QemuVmProvider(root, List::of).findAll()).id();

        Files.move(arch, root.resolve("Arch Renamed"));

        LocalVm renamed = only(new QemuVmProvider(root, List::of).findAll());
        assertEquals(id, renamed.id(), "a rename must not change the identity");
        assertEquals("Arch Renamed", renamed.name(), "the display name follows the directory");
    }

    @Test
    void diskImagesAreNotChangedByTheIdentity() throws IOException {
        Path arch = vm("ArchLinux");

        new QemuVmProvider(root, List::of).findAll();

        assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(arch.resolve(DISK)));
    }

    @Test
    void twoProvidersInstancesOfDifferentVmsGetDistinctIds() throws IOException {
        vm("ArchLinux");
        vm("FreeBSD");

        List<LocalVm> vms = new QemuVmProvider(root, List::of).findAll();

        assertEquals(2, vms.stream().map(LocalVm::id).distinct().count());
    }

    @Test
    void aCopiedDirectoryCarriesTheSameIdSoBothCopiesAreAmbiguous() throws IOException {
        Path original = vm("ArchLinux");
        String id = only(new QemuVmProvider(root, List::of).findAll()).id();

        copyDirectory(original, root.resolve("ArchLinux copy"));

        List<LocalVm> vms = new QemuVmProvider(root, List::of).findAll();
        assertEquals(2, vms.size());
        assertTrue(vms.stream().allMatch(vm -> vm.id().equals(id)),
                "a clone shares the identity; the inventory then hides both until one file is removed");
    }

    @Test
    void unreadableIdentityFileFallsBackToTheDirectoryNameWithoutOverwritingIt() throws IOException {
        Path arch = vm("ArchLinux");
        Files.writeString(arch.resolve(VmIdentitySidecar.FILE_NAME), "damaged");

        LocalVm discovered = only(new QemuVmProvider(root, List::of).findAll());

        assertEquals("ArchLinux", discovered.id(), "1.5.1 behaviour when no usable identity exists");
        assertEquals("damaged", Files.readString(arch.resolve(VmIdentitySidecar.FILE_NAME)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void readOnlyDirectoryFallsBackToTheDirectoryName() throws IOException {
        Path arch = vm("ArchLinux");
        Files.setPosixFilePermissions(arch, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(arch), "write permission cannot be removed for this user");

            LocalVm discovered = only(new QemuVmProvider(root, List::of).findAll());

            assertEquals("ArchLinux", discovered.id());
            assertFalse(Files.exists(arch.resolve(VmIdentitySidecar.FILE_NAME)));
        } finally {
            Files.setPosixFilePermissions(arch, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var entries = Files.list(source)) {
            for (Path entry : entries.toList()) {
                Files.copy(entry, target.resolve(entry.getFileName()));
            }
        }
    }
}
