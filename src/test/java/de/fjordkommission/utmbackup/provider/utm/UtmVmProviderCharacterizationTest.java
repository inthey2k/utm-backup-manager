package de.fjordkommission.utmbackup.provider.utm;

import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UtmVmProvider} discovery and runtime-state resolution.
 *
 * Discovery tests document 1.4.x behaviour that is unchanged. Status tests cover
 * the Phase 1 contract: statuses are keyed by the provider-local id (the package
 * folder name), and ambiguous or unmatched utmctl data never becomes STOPPED.
 *
 * A fake {@code utmctl} shell script stands in for the real CLI, so these tests
 * never touch an installed UTM. The fake command requires a POSIX shell, so the
 * class is limited to Linux and macOS.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class UtmVmProviderCharacterizationTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // findAll
    // ------------------------------------------------------------------

    @Test
    void findAllReturnsPackageFoldersWithFolderNameAsIdAndSortedByName() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        Files.createDirectories(vmDirectory.resolve("beta.utm"));
        Files.createDirectories(vmDirectory.resolve("Alpha.utm"));
        Files.createDirectories(vmDirectory.resolve("NotAPackage"));
        Files.writeString(vmDirectory.resolve("readme.utm"), "a file, not a package");

        UtmVmProvider provider = provider(vmDirectory, tempDir.resolve("utmctl"));

        List<LocalVm> vms = provider.findAll();

        assertEquals(List.of("Alpha.utm", "beta.utm"), vms.stream().map(LocalVm::id).toList());
        assertEquals(List.of("Alpha", "beta"), vms.stream().map(LocalVm::name).toList());
        assertEquals(
                List.of(vmDirectory.resolve("Alpha.utm")),
                vms.getFirst().backupSources()
        );
    }

    @Test
    void findAllReturnsEmptyListWhenVmDirectoryDoesNotExist() {
        UtmVmProvider provider = provider(tempDir.resolve("missing"), tempDir.resolve("utmctl"));

        assertTrue(provider.findAll().isEmpty());
    }

    @Test
    void providerIdAndDisplayNameAreStable() {
        UtmVmProvider provider = provider(tempDir.resolve("vms"), tempDir.resolve("utmctl"));

        assertEquals("utm", provider.providerId());
        assertEquals("UTM", provider.displayName());
    }

    // ------------------------------------------------------------------
    // findStatuses: keyed by local id
    // ------------------------------------------------------------------

    @Test
    void findStatusesIsKeyedByPackageFolderNameAndMapsUtmStates() throws IOException {
        Path vmDirectory = createPackages(
                "Alpha.utm", "Beta.utm", "Gamma.utm", "Delta.utm", "ArchLinux 02.27.utm"
        );
        Path utmctl = fakeUtmctl(
                """
                11111111-1111-1111-1111-111111111111 started Alpha
                22222222-2222-2222-2222-222222222222 stopped Beta
                33333333-3333-3333-3333-333333333333 paused Gamma
                44444444-4444-4444-4444-444444444444 weird Delta
                55555555-5555-5555-5555-555555555555 stopped ArchLinux 02.27
                this line is not a utmctl row
                """,
                0
        );

        Map<String, VmStatus> statuses = provider(vmDirectory, utmctl).findStatuses();

        assertEquals(
                Map.of(
                        "Alpha.utm", VmStatus.RUNNING,
                        "Beta.utm", VmStatus.STOPPED,
                        "Gamma.utm", VmStatus.RUNNING,
                        "Delta.utm", VmStatus.UNKNOWN,
                        "ArchLinux 02.27.utm", VmStatus.STOPPED
                ),
                statuses
        );
        assertFalse(statuses.containsKey("Alpha"), "display names must not be keys");
    }

    @Test
    void localVmWithoutUtmctlRowAndUtmctlRowWithoutLocalVmAreBothLeftOut() throws IOException {
        Path vmDirectory = createPackages("Epsilon.utm");
        Path utmctl = fakeUtmctl(
                "66666666-6666-6666-6666-666666666666 stopped Ghost\n",
                0
        );

        Map<String, VmStatus> statuses = provider(vmDirectory, utmctl).findStatuses();

        assertTrue(statuses.isEmpty(), "absent keys are read as UNKNOWN by the scanner");
    }

    @Test
    void duplicateUtmctlDisplayNamesResolveToUnknownWithoutThrowing() throws IOException {
        Path vmDirectory = createPackages("Same.utm", "Other.utm");
        Path utmctl = fakeUtmctl(
                """
                11111111-1111-1111-1111-111111111111 stopped Same
                22222222-2222-2222-2222-222222222222 started Same
                33333333-3333-3333-3333-333333333333 stopped Other
                """,
                0
        );

        Map<String, VmStatus> statuses = provider(vmDirectory, utmctl).findStatuses();

        assertEquals(VmStatus.UNKNOWN, statuses.get("Same.utm"));
        assertEquals(VmStatus.STOPPED, statuses.get("Other.utm"));
    }

    @Test
    void findStatusesIsEmptyWhenVmDirectoryIsMissing() throws IOException {
        Path utmctl = fakeUtmctl("11111111-1111-1111-1111-111111111111 stopped Alpha\n", 0);

        assertTrue(provider(tempDir.resolve("missing"), utmctl).findStatuses().isEmpty());
    }

    @Test
    void findStatusesReturnsEmptyMapWhenCommandFails() throws IOException {
        Path vmDirectory = createPackages("Alpha.utm");
        Path utmctl = fakeUtmctl("11111111-1111-1111-1111-111111111111 stopped Alpha\n", 1);

        assertTrue(provider(vmDirectory, utmctl).findStatuses().isEmpty());
    }

    @Test
    void findStatusesReturnsEmptyMapWhenExecutableIsMissing() throws IOException {
        Path vmDirectory = createPackages("Alpha.utm");
        Path utmctl = tempDir.resolve("does-not-exist");

        assertTrue(provider(vmDirectory, utmctl).findStatuses().isEmpty());
    }

    // ------------------------------------------------------------------
    // resolveStatuses: pure resolution rules
    // ------------------------------------------------------------------

    @Test
    void resolveStatusesMarksLocalVmsSharingADisplayNameAsUnknown() {
        List<LocalVm> locals = List.of(
                new LocalVm("First.utm", "Same", List.of()),
                new LocalVm("Second.utm", "Same", List.of())
        );
        List<UtmVmProvider.UtmctlEntry> entries = List.of(
                new UtmVmProvider.UtmctlEntry("Same", "stopped")
        );

        Map<String, VmStatus> statuses = UtmVmProvider.resolveStatuses(locals, entries);

        assertEquals(VmStatus.UNKNOWN, statuses.get("First.utm"));
        assertEquals(VmStatus.UNKNOWN, statuses.get("Second.utm"));
    }

    @Test
    void resolveStatusesMatchesUniqueDisplayNameWithSpacesAndDots() {
        List<LocalVm> locals = List.of(new LocalVm("ArchLinux 02.27.utm", "ArchLinux 02.27", List.of()));
        List<UtmVmProvider.UtmctlEntry> entries = List.of(
                new UtmVmProvider.UtmctlEntry("ArchLinux 02.27", "stopped")
        );

        assertEquals(
                Map.of("ArchLinux 02.27.utm", VmStatus.STOPPED),
                UtmVmProvider.resolveStatuses(locals, entries)
        );
    }

    @Test
    void resolveStatusesMarksRepeatedUtmctlRowsAsUnknownEvenWithOneLocalVm() {
        List<LocalVm> locals = List.of(new LocalVm("Only.utm", "Only", List.of()));
        List<UtmVmProvider.UtmctlEntry> entries = List.of(
                new UtmVmProvider.UtmctlEntry("Only", "stopped"),
                new UtmVmProvider.UtmctlEntry("Only", "stopped")
        );

        assertEquals(
                Map.of("Only.utm", VmStatus.UNKNOWN),
                UtmVmProvider.resolveStatuses(locals, entries)
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Path createPackages(String... folderNames) throws IOException {
        Path vmDirectory = tempDir.resolve("vms");

        for (String folderName : folderNames) {
            Files.createDirectories(vmDirectory.resolve(folderName));
        }

        return vmDirectory;
    }

    private static UtmVmProvider provider(Path vmDirectory, Path utmctl) {
        return new UtmVmProvider(
                new VmProperties(
                        null,
                        null,
                        new VmProperties.Utm(vmDirectory, ".utm", utmctl)
                )
        );
    }

    /**
     * Writes a POSIX shell script that prints the given output and exits with
     * the given code, regardless of the arguments it receives.
     */
    private Path fakeUtmctl(String output, int exitCode) throws IOException {
        Path script = tempDir.resolve("utmctl");

        Files.writeString(
                script,
                "#!/bin/sh\ncat <<'EOF'\n" + output + "EOF\nexit " + exitCode + "\n"
        );
        Files.setPosixFilePermissions(
                script,
                PosixFilePermissions.fromString("rwxr-xr-x")
        );

        return script;
    }
}
