package de.fjordkommission.utmbackup.provider.utm;

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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UtmVmProvider} discovery and runtime-state resolution.
 * The local id is the native UUID from {@code config.plist}; a package without a
 * readable UUID keeps its folder name. Statuses are keyed by that local id, matched by
 * UUID and never by display name, and ambiguous or unmatched utmctl data never becomes
 * STOPPED.
 * A fake {@code utmctl} shell script stands in for the real CLI, so these tests
 * never touch an installed UTM. The fake command requires a POSIX shell, so the
 * class is limited to Linux and macOS.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class UtmVmProviderCharacterizationTest {

    private static final String UUID_ALPHA = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_BETA = "22222222-2222-2222-2222-222222222222";
    private static final String UUID_GAMMA = "33333333-3333-3333-3333-333333333333";
    private static final String UUID_DELTA = "44444444-4444-4444-4444-444444444444";
    /** Upper case in config.plist, lower case in utmctl output: the match must ignore case. */
    private static final String UUID_EPSILON = "ABCDEF01-2345-6789-ABCD-EF0123456789";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // findAll
    // ------------------------------------------------------------------

    @Test
    void findAllFallsBackToFolderNameWithoutConfigAndSortsByName() throws IOException {
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

    @Test
    void findAllUsesNativeUuidAsLocalIdAndFolderNameAsDisplayName() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        writePackage(vmDirectory.resolve("Linux Mint 23.utm"), UUID_ALPHA);

        List<LocalVm> vms = provider(vmDirectory, tempDir.resolve("utmctl")).findAll();

        assertEquals(List.of(UUID_ALPHA), vms.stream().map(LocalVm::id).toList());
        assertEquals(List.of("Linux Mint 23"), vms.stream().map(LocalVm::name).toList());
    }

    @Test
    void renamedPackageKeepsItsUuidSoItsIdentityDoesNotChange() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        writePackage(vmDirectory.resolve("Linux Mint 23.utm"), UUID_ALPHA);
        UtmVmProvider provider = provider(vmDirectory, tempDir.resolve("utmctl"));

        assertEquals(UUID_ALPHA, provider.findAll().getFirst().id());

        Files.move(vmDirectory.resolve("Linux Mint 23.utm"), vmDirectory.resolve("Linux Mint 27.utm"));

        LocalVm renamed = provider.findAll().getFirst();
        assertEquals(UUID_ALPHA, renamed.id());
        assertEquals("Linux Mint 27", renamed.name());
    }

    @Test
    void findAllKeepsFolderNameWhenConfigIsNotReadable() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        Files.createDirectories(vmDirectory.resolve("Binary.utm"));
        Files.write(vmDirectory.resolve("Binary.utm").resolve("config.plist"),
                new byte[] {'b', 'p', 'l', 'i', 's', 't', '0', '0'});
        Path doctypeEntity = Files.createDirectories(vmDirectory.resolve("Entity.utm"));
        Files.writeString(doctypeEntity.resolve("config.plist"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist [<!ENTITY id SYSTEM "file:///etc/hostname">]>
                <plist version="1.0"><dict><key>Information</key><dict><key>UUID</key><string>&id;</string></dict></dict></plist>
                """);
        Path notAUuid = Files.createDirectories(vmDirectory.resolve("Short.utm"));
        Files.writeString(notAUuid.resolve("config.plist"), plist("not-a-uuid"));

        List<LocalVm> vms = provider(vmDirectory, tempDir.resolve("utmctl")).findAll();

        assertEquals(List.of("Binary.utm", "Entity.utm", "Short.utm"),
                vms.stream().map(LocalVm::id).toList(),
                "unreadable identities fall back to the folder name and are never guessed");
    }

    // ------------------------------------------------------------------
    // findStatuses: keyed by local id
    // ------------------------------------------------------------------

    @Test
    void findStatusesIsKeyedByNativeUuidAndMapsUtmStates() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        writePackage(vmDirectory.resolve("Alpha.utm"), UUID_ALPHA);
        writePackage(vmDirectory.resolve("Beta.utm"), UUID_BETA);
        writePackage(vmDirectory.resolve("Gamma.utm"), UUID_GAMMA);
        writePackage(vmDirectory.resolve("Delta.utm"), UUID_DELTA);
        writePackage(vmDirectory.resolve("ArchLinux 02.27.utm"), UUID_EPSILON);

        Path utmctl = fakeUtmctl(
                """
                11111111-1111-1111-1111-111111111111 started Alpha
                22222222-2222-2222-2222-222222222222 stopped Beta
                33333333-3333-3333-3333-333333333333 paused Gamma
                44444444-4444-4444-4444-444444444444 weird Delta
                abcdef01-2345-6789-abcd-ef0123456789 stopped ArchLinux 02.27
                this line is not a utmctl row
                """,
                0
        );

        Map<String, VmStatus> statuses = provider(vmDirectory, utmctl).findStatuses();

        assertEquals(
                Map.of(
                        UUID_ALPHA, VmStatus.RUNNING,
                        UUID_BETA, VmStatus.STOPPED,
                        UUID_GAMMA, VmStatus.RUNNING,
                        UUID_DELTA, VmStatus.UNKNOWN,
                        UUID_EPSILON, VmStatus.STOPPED
                ),
                statuses
        );
        assertFalse(statuses.containsKey("Alpha.utm"), "folder names must not be keys");
        assertFalse(statuses.containsKey("Alpha"), "display names must not be keys");
    }

    @Test
    void localVmWithoutUtmctlRowAndUtmctlRowWithoutLocalVmAreBothLeftOut() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        writePackage(vmDirectory.resolve("Epsilon.utm"), UUID_ALPHA);
        Path utmctl = fakeUtmctl(
                "66666666-6666-6666-6666-666666666666 stopped Ghost\n",
                0
        );

        Map<String, VmStatus> statuses = provider(vmDirectory, utmctl).findStatuses();

        assertTrue(statuses.isEmpty(), "absent keys are read as UNKNOWN by the scanner");
    }

    @Test
    void duplicateUtmctlRowsForOneUuidResolveToUnknownWithoutThrowing() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        writePackage(vmDirectory.resolve("Same.utm"), UUID_ALPHA);
        writePackage(vmDirectory.resolve("Other.utm"), UUID_BETA);
        Path utmctl = fakeUtmctl(
                """
                11111111-1111-1111-1111-111111111111 stopped Same
                11111111-1111-1111-1111-111111111111 started Same
                22222222-2222-2222-2222-222222222222 stopped Other
                """,
                0
        );

        Map<String, VmStatus> statuses = provider(vmDirectory, utmctl).findStatuses();

        assertEquals(VmStatus.UNKNOWN, statuses.get(UUID_ALPHA));
        assertEquals(VmStatus.STOPPED, statuses.get(UUID_BETA));
    }

    @Test
    void findStatusesIsEmptyWhenVmDirectoryIsMissing() throws IOException {
        Path utmctl = fakeUtmctl("", 0);

        assertTrue(provider(tempDir.resolve("missing"), utmctl).findStatuses().isEmpty());
    }

    @Test
    void findStatusesReturnsEmptyMapWhenCommandFails() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        writePackage(vmDirectory.resolve("Alpha.utm"), UUID_ALPHA);
        Path utmctl = fakeUtmctl("11111111-1111-1111-1111-111111111111 started Alpha\n", 1);

        assertTrue(provider(vmDirectory, utmctl).findStatuses().isEmpty());
    }

    @Test
    void findStatusesReturnsEmptyMapWhenExecutableIsMissing() throws IOException {
        Path vmDirectory = tempDir.resolve("vms");
        writePackage(vmDirectory.resolve("Alpha.utm"), UUID_ALPHA);
        Path utmctl = tempDir.resolve("does-not-exist");

        assertTrue(provider(vmDirectory, utmctl).findStatuses().isEmpty());
    }

    // ------------------------------------------------------------------
    // resolveStatuses: pure resolution rules
    // ------------------------------------------------------------------

    @Test
    void resolveStatusesMarksLocalVmsSharingAUuidAsUnknown() {
        List<LocalVm> locals = List.of(
                new LocalVm(UUID_ALPHA, "First", List.of()),
                new LocalVm(UUID_ALPHA, "Second", List.of())
        );
        List<UtmVmProvider.UtmctlEntry> entries = List.of(
                new UtmVmProvider.UtmctlEntry(UUID_ALPHA, "stopped")
        );

        Map<String, VmStatus> statuses = UtmVmProvider.resolveStatuses(locals, entries);

        assertEquals(VmStatus.UNKNOWN, statuses.get(UUID_ALPHA));
    }

    @Test
    void resolveStatusesIgnoresADifferentUuidWithTheSameDisplayName() {
        List<LocalVm> locals = List.of(new LocalVm(UUID_ALPHA, "Same", List.of()));
        List<UtmVmProvider.UtmctlEntry> entries = List.of(
                new UtmVmProvider.UtmctlEntry(UUID_BETA, "stopped")
        );

        assertTrue(UtmVmProvider.resolveStatuses(locals, entries).isEmpty(),
                "matching is by UUID only, so a name collision is not a match");
    }

    @Test
    void resolveStatusesMatchesUuidCaseInsensitively() {
        List<LocalVm> locals = List.of(new LocalVm(UUID_EPSILON, "ArchLinux 02.27", List.of()));
        List<UtmVmProvider.UtmctlEntry> entries = List.of(
                new UtmVmProvider.UtmctlEntry(UUID_EPSILON.toLowerCase(Locale.ROOT), "stopped")
        );

        assertEquals(
                Map.of(UUID_EPSILON, VmStatus.STOPPED),
                UtmVmProvider.resolveStatuses(locals, entries)
        );
    }

    @Test
    void resolveStatusesMarksRepeatedUtmctlRowsAsUnknownEvenWithOneLocalVm() {
        List<LocalVm> locals = List.of(new LocalVm(UUID_ALPHA, "Only", List.of()));
        List<UtmVmProvider.UtmctlEntry> entries = List.of(
                new UtmVmProvider.UtmctlEntry(UUID_ALPHA, "stopped"),
                new UtmVmProvider.UtmctlEntry(UUID_ALPHA, "stopped")
        );

        assertEquals(
                Map.of(UUID_ALPHA, VmStatus.UNKNOWN),
                UtmVmProvider.resolveStatuses(locals, entries)
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Creates a package directory with a {@code config.plist} carrying the given UUID. */
    private static void writePackage(Path packageDirectory, String uuid) throws IOException {
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("config.plist"), plist(uuid));
    }

    private static String plist(String uuid) {
        return UtmConfigFixture.config("Test VM", uuid);
    }

    private static UtmVmProvider provider(Path vmDirectory, Path utmctl) {
        return new UtmVmProvider(new UtmProperties(vmDirectory, ".utm", utmctl));
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
