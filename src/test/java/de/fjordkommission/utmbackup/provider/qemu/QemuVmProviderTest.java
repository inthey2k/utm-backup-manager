package de.fjordkommission.utmbackup.provider.qemu;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProvider;
import org.junit.jupiter.api.Assumptions;
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

/**
 * Discovery rules of the QEMU provider on a temporary VM root.
 */
class QemuVmProviderTest {

    private static final String ARCH = "ArchLinux";
    private static final String FREEBSD = "FreeBSD";
    private static final String ARCH_DISK = "arch-linux-disk.qcow2";

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tempDir.resolve("VMs"));
    }

    @Test
    void identifiesItselfAsQemu() {
        QemuVmProvider provider = new QemuVmProvider(root, List::of);

        assertEquals("qemu", provider.providerId());
        assertEquals("QEMU", provider.displayName());
    }

    @Test
    void eachDirectoryWithADiskImageIsOneVmWithItsNameAsLocalId() throws IOException {
        vm(FREEBSD, "FreeBSD-15.1.qcow2");
        vm(ARCH, ARCH_DISK);

        List<LocalVm> vms = new QemuVmProvider(root, List::of).findAll();

        assertEquals(List.of(ARCH, FREEBSD), vms.stream().map(LocalVm::id).toList());
        LocalVm arch = vms.getFirst();
        assertEquals(ARCH, arch.name());
        assertEquals(List.of(root.resolve(ARCH)), arch.backupSources());
    }

    @Test
    void diskFileNameNeedNotMatchTheVmName() throws IOException {
        vm(ARCH, ARCH_DISK);

        LocalVm arch = new QemuVmProvider(root, List::of).findAll().getFirst();

        assertEquals(ARCH, arch.id());
    }

    @Test
    void supportsRawAndUpperCaseDiskImages() throws IOException {
        vm("Raw", "disk.raw");
        vm("Upper", "DISK.QCOW2");

        assertEquals(
                List.of("Raw", "Upper"),
                new QemuVmProvider(root, List::of).findAll().stream().map(LocalVm::id).toList()
        );
    }

    @Test
    void ordinaryFilesAtTheRootLevelAreIgnored() throws IOException {
        Files.writeString(root.resolve("notes.txt"), "not a VM");
        Files.writeString(root.resolve("Loose.qcow2"), "a disk, but not in a VM directory");

        assertEquals(List.of(), new QemuVmProvider(root, List::of).findAll());
    }

    @Test
    void directoriesWithoutDiskImageAndHiddenEntriesAreIgnored() throws IOException {
        Files.createDirectories(root.resolve("Empty"));
        Files.writeString(root.resolve("Empty").resolve("config.plist"), "no disk here");
        vm(".Trashes", "disk.qcow2");
        Path metadataOnly = Files.createDirectories(root.resolve("MetadataOnly"));
        Files.writeString(metadataOnly.resolve("._disk.qcow2"), "AppleDouble file");
        vm("Real", "real.qcow2");

        assertEquals(
                List.of("Real"),
                new QemuVmProvider(root, List::of).findAll().stream().map(LocalVm::id).toList()
        );
    }

    @Test
    void doesNotTreatNestedDirectoriesAsVms() throws IOException {
        Path nested = Files.createDirectories(root.resolve("Outer").resolve("Inner"));
        Files.writeString(nested.resolve("disk.qcow2"), "nested disk");

        assertEquals(List.of(), new QemuVmProvider(root, List::of).findAll());
    }

    @Test
    void missingVmRootIsAProviderError() {
        QemuVmProvider provider = new QemuVmProvider(tempDir.resolve("missing"), List::of);

        IllegalStateException error = assertThrows(IllegalStateException.class, provider::findAll);

        assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
    }

    @Test
    void missingConfigurationIsAProviderErrorNamingTheProperty() {
        QemuVmProvider provider = new QemuVmProvider(null, List::of);

        IllegalStateException error = assertThrows(IllegalStateException.class, provider::findAll);

        assertTrue(error.getMessage().contains("vm.qemu.directory"), error.getMessage());
    }

    @Test
    void unreadableVmDirectoryFailsTheListingAndNamesTheDirectory() throws IOException {
        Path locked = vm("Locked", "disk.qcow2");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("-w-------"));

        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "Readability cannot be restricted for this user");

            QemuVmProvider provider = new QemuVmProvider(root, List::of);

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    provider::findAll
            );

            assertTrue(error.getMessage().contains("Locked"), error.getMessage());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void configurationShowsTheVmRoot() {
        assertEquals(
                List.of(new VmProvider.ConfigurationEntry("localVmDirectory", "config.vmDirectory.label", root.toString())),
                new QemuVmProvider(root, List::of).configuration()
        );
    }

    @Test
    void statusMapIsKeyedByLocalIdOfDiscoveredVms() throws IOException {
        vm(ARCH, ARCH_DISK);
        vm(FREEBSD, "FreeBSD-15.1.qcow2");
        QemuVmProvider provider = new QemuVmProvider(root, () -> List.of(
                new QemuProcess(
                        "/opt/homebrew/bin/qemu-system-aarch64",
                        List.of("qemu-system-aarch64", "-hda", root.resolve(ARCH).resolve(ARCH_DISK).toString())
                )
        ));

        assertEquals(
                Map.of(ARCH, VmStatus.RUNNING, FREEBSD, VmStatus.STOPPED),
                provider.findStatuses()
        );
    }

    @Test
    void runningVmIsRecognisedWhenQemuUsesTheRealPathOfASymbolicLinkedRoot() throws IOException {
        Path realRoot = Files.createDirectories(tempDir.resolve("Real VMs"));
        Path arch = Files.createDirectories(realRoot.resolve(ARCH));
        Files.writeString(arch.resolve(ARCH_DISK), "disk");
        Path link = Files.createSymbolicLink(tempDir.resolve("Linked VMs"), realRoot);
        QemuVmProvider provider = new QemuVmProvider(link, () -> List.of(
                new QemuProcess("/opt/homebrew/bin/qemu-system-aarch64", List.of(
                        "qemu-system-aarch64", "-hda", arch.resolve(ARCH_DISK).toString()
                ))
        ));

        assertEquals(Map.of(ARCH, VmStatus.RUNNING), provider.findStatuses());
    }

    @Test
    void runningVmIsRecognisedWhenQemuNamesItsDiskThroughAnAliasedDirectory() throws IOException {
        // The VM root and the process argument spell the same directory differently,
        // as /var and /private/var do on macOS. The argument must still match.
        Path realRoot = Files.createDirectories(tempDir.resolve("Real VMs"));
        Path arch = Files.createDirectories(realRoot.resolve(ARCH));
        Files.writeString(arch.resolve(ARCH_DISK), "disk");
        Path alias = Files.createSymbolicLink(tempDir.resolve("Alias VMs"), realRoot);
        QemuVmProvider provider = new QemuVmProvider(realRoot, () -> List.of(
                new QemuProcess("/opt/homebrew/bin/qemu-system-aarch64", List.of(
                        "qemu-system-aarch64", "-hda", alias.resolve(ARCH).resolve(ARCH_DISK).toString()
                ))
        ));

        assertEquals(Map.of(ARCH, VmStatus.RUNNING), provider.findStatuses());
    }

    private Path vm(String name, String diskName) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        Files.writeString(directory.resolve(diskName), "disk of " + name);
        return directory;
    }
}
