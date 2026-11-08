package de.fjordkommission.utmbackup.provider.utm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Name</key>
                    <string>Linux</string>
                    <key>UUID</key>
                    <string>%s</string>
                </dict>
                </plist>
                """.formatted(uuid);
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
    void nestedUuidIsNotTheTopLevelIdentity() throws IOException {
        Path nested = pkg("nested.utm", """
                <plist version="1.0"><dict>
                    <key>Drive</key><dict><key>UUID</key><string>%s</string></dict>
                </dict></plist>
                """.formatted(UUID));

        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(nested));
    }

    @Test
    void missingConfigBinaryConfigAndMalformedUuidAreEmpty() throws IOException {
        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(tempDir.resolve("missing.utm")));
        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(
                pkg("binary.utm", "bplist00\u0000\u0001")));
        assertEquals(Optional.empty(), UtmPackageIdentity.readPackageUuid(pkg("short.utm", plist("1234"))));
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
