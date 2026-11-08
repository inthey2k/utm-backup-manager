package de.fjordkommission.utmbackup.provider.utm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native UTM identity read from {@code config.plist}. Only well-formed XML plists with a
 * top-level string UUID count; anything else yields empty so no identity is guessed.
 */
class UtmPackageIdentityTest {

    private static final String UUID = "6c1f2a3b-0000-4000-8000-00000000a003";

    @TempDir
    Path tempDir;

    private static String plist(String uuid) {
        return UtmConfigFixture.config("Linux", uuid);
    }

    private Path pkg(String name, String content) throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(pkg.resolve("config.plist"), content);
        return pkg;
    }

    @Test
    void readsTheTopLevelUuidInUpperCase() throws IOException {
        assertEquals(Optional.of(UUID.toUpperCase(java.util.Locale.ROOT)),
                UtmPackageIdentity.readPackageUuid(pkg("a.utm", plist(UUID))));
    }

    @Test
    void readsTheUuidOfTheInformationDictionaryOfARealConfiguration() throws IOException {
        Path pkg = pkg("Linux Mint 23.utm", plist(UUID));

        assertEquals(Optional.of(UUID.toUpperCase(Locale.ROOT)), UtmPackageIdentity.readPackageUuid(pkg));
    }

    @Test
    void aTopLevelUuidOrAUuidInsideAnotherDictionaryIsNotTheIdentity() throws IOException {
        Path topLevel = pkg("top.utm", """
                <plist version="1.0"><dict>
                    <key>UUID</key><string>%s</string>
                </dict></plist>
                """.formatted(UUID));
        Path drive = pkg("drive.utm", """
                <plist version="1.0"><dict>
                    <key>Information</key><dict><key>Name</key><string>Linux</string></dict>
                    <key>Drive</key><array><dict><key>Identifier</key><string>%s</string></dict></array>
                </dict></plist>
                """.formatted(UUID));

        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(topLevel));
        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(drive));
    }

    @Test
    void informationUuidThatIsNotAStringIsIgnored() throws IOException {
        Path pkg = pkg("integer.utm", """
                <plist version="1.0"><dict>
                    <key>Information</key><dict><key>UUID</key><integer>42</integer></dict>
                </dict></plist>
                """);

        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(pkg));
    }

    @Test
    void missingConfigBinaryConfigAndMalformedUuidAreEmpty() throws IOException {
        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(tempDir.resolve("missing.utm")));
        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(
                pkg("binary.utm", "bplist00\u0000\u0001")));
        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(pkg("short.utm", plist("1234"))));
    }

    @Test
    void malformedConfigFailsQuietlyWithoutWritingToStderr() throws IOException {
        Path binary = pkg("binary.utm", "bplist00 not xml at all <");
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.err;

        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(binary));
        } finally {
            System.setErr(original);
        }

        assertEquals("", captured.toString(java.nio.charset.StandardCharsets.UTF_8),
                "expected parser failures must not be printed");
    }

    @Test
    void containerWithOnePackageReturnsItsUuid() throws IOException {
        Path container = Files.createDirectories(tempDir.resolve("container"));
        Files.createDirectories(container.resolve("Linux.utm"));
        Files.writeString(container.resolve("Linux.utm").resolve("config.plist"), plist(UUID));

        assertEquals(Optional.of(UUID.toUpperCase(java.util.Locale.ROOT)),
                UtmPackageIdentity.readContainerUuid(container));
    }

    @Test
    void containerWithConflictingPackagesIsEmpty() throws IOException {
        Path container = Files.createDirectories(tempDir.resolve("conflict"));
        Files.createDirectories(container.resolve("One.utm"));
        Files.writeString(container.resolve("One.utm").resolve("config.plist"), plist(UUID));
        Files.createDirectories(container.resolve("Two.utm"));
        Files.writeString(container.resolve("Two.utm").resolve("config.plist"),
                plist("aaaaaaaa-0000-4000-8000-000000000001"));

        assertEquals(Optional.empty(), UtmPackageIdentity.readContainerUuid(container));
    }

    @Test
    void emptyOrMissingContainerIsEmpty() throws IOException {
        assertEquals(Optional.empty(), UtmPackageIdentity.readContainerUuid(tempDir.resolve("none")));
        assertEquals(Optional.empty(),
                UtmPackageIdentity.readContainerUuid(Files.createDirectories(tempDir.resolve("empty"))));
    }
}
