package de.fjordkommission.utmbackup.provider.utm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Custom icons follow the verified UTM layout on macOS: {@code Information/Icon} names a file in
 * {@code <package>/Data/}, and {@code Information/IconCustom} is true. The file is never looked
 * up in the package root.
 */
class UtmPackageIconTest {

    private static final String UUID = "5A8B7C6D-1234-4ABC-9DEF-0123456789AB";

    @TempDir
    Path tempDirectory;

    @Test
    void customIconInTheDataFolderIsReturned() throws IOException {
        Path vm = packageWith(customConfig("omarchy.png"));
        Path icon = Files.write(vm.resolve("Data/omarchy.png"), new byte[] {1, 2, 3});

        assertEquals(Optional.of(icon.toRealPath()), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void iconInThePackageRootIsNotUsed() throws IOException {
        Path vm = packageWith(customConfig("omarchy.png"));
        Files.write(vm.resolve("omarchy.png"), new byte[] {1});

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void defaultIconNameIsNotAssumedWhenIconIsAbsent() throws IOException {
        Path vm = packageWith(customConfigWithoutIconName());
        Files.write(vm.resolve("Data/icon.png"), new byte[] {1});

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void builtInIconIsNotReadFromThePackageEvenIfAFileExists() throws IOException {
        // IconCustom is false for built-in icons, so no file is used even if one exists.
        Path vm = packageWith(UtmConfigFixture.config("Linux", UUID));
        Files.write(vm.resolve("Data/linux.png"), new byte[] {1});

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void missingCustomIconFileYieldsTheFallback() throws IOException {
        Path vm = packageWith(customConfig("omarchy.png"));

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void iconNameOutsideTheDataFolderIsRejected() throws IOException {
        Path vm = packageWith(customConfig("../omarchy.png"));
        Files.write(vm.resolve("omarchy.png"), new byte[] {1});
        Files.write(tempDirectory.resolve("omarchy.png"), new byte[] {1});

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void unsupportedImageTypeIsRejected() throws IOException {
        Path vm = packageWith(customConfig("omarchy.bmp"));
        Files.write(vm.resolve("Data/omarchy.bmp"), new byte[] {1});

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void symbolicLinkLeavingThePackageIsRejected() throws IOException {
        Path vm = packageWith(customConfig("omarchy.png"));
        Path outside = Files.write(tempDirectory.resolve("outside.png"), new byte[] {1});
        try {
            Files.createSymbolicLink(vm.resolve("Data/omarchy.png"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Symbolic links are unavailable on this file system; the containment check is then not reachable.
            return;
        }

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void packageWithoutConfigurationHasNoIcon() throws IOException {
        Path vm = tempDirectory.resolve("Empty.utm");
        Files.createDirectories(vm);

        assertFalse(UtmPackageIdentity.readCustomIcon(vm).isPresent());
    }

    /** The configuration as UTM writes it for a custom icon named {@code iconFile}. */
    private static String customConfig(String iconFile) {
        return UtmConfigFixture.config("Omarchy Mac", UUID)
                .replaceFirst("<key>IconCustom</key>\\s*<false/>", "<key>IconCustom</key>\\n\\t\\t<true/>")
                .replaceFirst("<key>Icon</key>\\s*<string>linux</string>",
                        "<key>Icon</key>\\n\\t\\t<string>" + iconFile + "</string>");
    }

    private static String customConfigWithoutIconName() {
        return UtmConfigFixture.config("Omarchy Mac", UUID)
                .replaceFirst("<key>IconCustom</key>\\s*<false/>", "<key>IconCustom</key>\\n\\t\\t<true/>")
                .replaceFirst("\\t\\t<key>Icon</key>\\s*<string>linux</string>\\n", "");
    }

    private Path packageWith(String configuration) throws IOException {
        Path vm = tempDirectory.resolve("Omarchy Mac.utm");
        Files.createDirectories(vm.resolve("Data"));
        Files.writeString(vm.resolve("config.plist"), configuration);
        return vm;
    }
}
