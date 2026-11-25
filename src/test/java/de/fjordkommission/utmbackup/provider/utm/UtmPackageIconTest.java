package de.fjordkommission.utmbackup.provider.utm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtmPackageIconTest {

    private static final String UUID = "5A8B7C6D-1234-4ABC-9DEF-0123456789AB";

    @TempDir
    Path tempDirectory;

    @Test
    void customIconConfiguredInThePackageIsReturned() throws IOException {
        Path vm = packageWith(customConfig("icon.png"));
        Path icon = Files.write(vm.resolve("icon.png"), new byte[] {1, 2, 3});

        assertEquals(Optional.of(icon), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void builtInIconNameIsNotAFileOfThePackage() throws IOException {
        // IconCustom is false for built-in icons, so no file is used even if one exists.
        Path vm = packageWith(UtmConfigFixture.config("Linux", UUID));
        Files.write(vm.resolve("icon.png"), new byte[] {1});

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void missingCustomIconFileYieldsTheFallback() throws IOException {
        Path vm = packageWith(customConfig("icon.png"));

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void iconNameOutsideThePackageIsNotFollowed() throws IOException {
        Path vm = packageWith(customConfig("../outside.png"));
        Files.write(tempDirectory.resolve("outside.png"), new byte[] {1});

        // The unsafe name is ignored; the default name inside the package is used instead, and it is missing.
        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void unsupportedImageTypeIsRejected() throws IOException {
        Path vm = packageWith(customConfig("icon.bmp"));
        Files.write(vm.resolve("icon.bmp"), new byte[] {1});

        assertEquals(Optional.empty(), UtmPackageIdentity.readCustomIcon(vm));
    }

    @Test
    void packageWithoutConfigurationHasNoIcon() throws IOException {
        Path vm = tempDirectory.resolve("Empty.utm");
        Files.createDirectories(vm);

        assertFalse(UtmPackageIdentity.readCustomIcon(vm).isPresent());
    }

    @Test
    void customIconFlagSetToTrueSelectsTheDefaultImageFile() throws IOException {
        Path vm = packageWith(UtmConfigFixture.config("Linux", UUID)
                .replaceFirst("<key>IconCustom</key>\\s*<false/>", "<key>IconCustom</key>\\n\\t\\t<true/>"));
        Files.write(vm.resolve("icon.png"), new byte[] {1});

        assertTrue(UtmPackageIdentity.readCustomIcon(vm).isPresent());
    }

    /** Writes config.plist with a custom icon set to the given file name. */
    private String customConfig(String iconFile) {
        return UtmConfigFixture.config("Linux", UUID)
                .replaceFirst("<key>IconCustom</key>\\s*<false/>", "<key>IconCustom</key>\\n\\t\\t<true/>")
                .replaceFirst("<key>Icon</key>\\s*<string>linux</string>",
                        "<key>Icon</key>\\n\\t\\t<string>" + iconFile + "</string>");
    }

    private Path packageWith(String configuration) throws IOException {
        Path vm = tempDirectory.resolve("Linux.utm");
        Files.createDirectories(vm);
        Files.writeString(vm.resolve("config.plist"), configuration);
        return vm;
    }
}
