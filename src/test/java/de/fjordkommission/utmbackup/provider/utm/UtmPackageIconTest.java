package de.fjordkommission.utmbackup.provider.utm;

import de.fjordkommission.utmbackup.model.LocalVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Icons of UTM VMs, using the verified macOS layout. A custom icon is {@code Data/<Icon>} inside
 * the package. A built-in icon is UTM's {@code Contents/Resources/Icons/<Icon>.png}, found from the
 * real path of {@code utmctl}. Fixtures are temporary, and no UTM asset is copied.
 */
class UtmPackageIconTest {

    private static final String UUID = "5A8B7C6D-1234-4ABC-9DEF-0123456789AB";

    @TempDir
    Path tempDirectory;

    @Test
    void customIconInTheDataFolderIsReturned() throws IOException {
        Path pkg = vmPackage(config(true, "omarchy.png"));
        Path icon = Files.write(pkg.resolve("Data/omarchy.png"), new byte[] {1, 2, 3});

        assertEquals(Optional.of(icon.toRealPath()), iconOf(pkg, utmctlInApp()));
    }

    /**
     * Custom icons that must not be shown: a file outside the Data folder, a missing icon name, a
     * missing file, and a name that leaves the Data folder. Each case has a file in the given folder
     * (or none, when null).
     */
    static Stream<Arguments> unusableCustomIcons() {
        return Stream.of(
                Arguments.of("file only in the package root", "omarchy.png", ""),
                Arguments.of("icon name absent", null, "Data"),
                Arguments.of("file missing", "omarchy.png", null),
                Arguments.of("name leaves the Data folder", "../omarchy.png", "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableCustomIcons")
    void unusableCustomIconFallsBackToInitials(String description, String iconName, String folder)
            throws IOException {
        Path pkg = vmPackage(config(true, iconName));

        if (folder != null) {
            Files.write(pkg.resolve(folder).resolve("omarchy.png"), new byte[] {1});
        }

        assertEquals(Optional.empty(), iconOf(pkg, utmctlInApp()));
    }

    @Test
    void unsupportedCustomImageTypeIsRejected() throws IOException {
        Path pkg = vmPackage(config(true, "omarchy.bmp"));
        Files.write(pkg.resolve("Data/omarchy.bmp"), new byte[] {1});

        assertEquals(Optional.empty(), iconOf(pkg, utmctlInApp()));
    }

    @Test
    void customIconSymbolicLinkLeavingThePackageIsRejected() throws IOException {
        Path pkg = vmPackage(config(true, "omarchy.png"));
        Path outside = Files.write(tempDirectory.resolve("outside.png"), new byte[] {1});
        Files.createSymbolicLink(pkg.resolve("Data/omarchy.png"), outside);

        assertEquals(Optional.empty(), iconOf(pkg, utmctlInApp()));
    }

    @Test
    void builtInIconIsReadFromUtmsIconFolderNextToUtmctl() throws IOException {
        Path utmctl = utmctlInApp();
        Path icon = builtIn("alpine.png");
        Path pkg = vmPackage(config(false, "alpine"));

        assertEquals(Optional.of(icon.toRealPath()), iconOf(pkg, utmctl));
    }

    @Test
    void builtInIconIsFoundThroughASymbolicLinkedUtmctl() throws IOException {
        // Homebrew links the command into its bin folder; the icon folder comes from the real file.
        Path utmctl = utmctlInApp();
        Path link = Files.createDirectories(tempDirectory.resolve("homebrew/bin")).resolve("utmctl");
        Files.createSymbolicLink(link, utmctl);
        Path icon = builtIn("almalinux.png");
        Path pkg = vmPackage(config(false, "almalinux"));

        assertEquals(Optional.of(icon.toRealPath()), iconOf(pkg, link));
    }

    @Test
    void configuredIconDirectoryOverridesTheDerivedOne() throws IOException {
        Path custom = Files.createDirectories(tempDirectory.resolve("elsewhere/Icons"));
        Path icon = Files.write(custom.resolve("ubuntu.png"), new byte[] {1});
        Path pkg = vmPackage(config(false, "ubuntu"));

        UtmVmProvider provider = provider(tempDirectory.resolve("plain/utmctl"), custom);

        assertEquals(Optional.of(icon.toRealPath()), provider.iconFile(vm(pkg)));
    }

    @Test
    void builtInNameWithoutExtensionGetsThePngSuffix() throws IOException {
        Path icon = builtIn("arch-linux.png");
        Path pkg = vmPackage(config(false, "arch-linux"));

        assertEquals(Optional.of(icon.toRealPath()), iconOf(pkg, utmctlInApp()));
    }

    @Test
    void missingBuiltInIconFallsBack() throws IOException {
        builtIn("alpine.png");
        Path pkg = vmPackage(config(false, "windows"));

        assertEquals(Optional.empty(), iconOf(pkg, utmctlInApp()));
    }

    @Test
    void builtInNameWithPathPartsOrOtherSuffixIsRejected() throws IOException {
        builtIn("alpine.png");
        Files.write(tempDirectory.resolve("secret.png"), new byte[] {1});
        Path traversal = vmPackage(config(false, "../../secret"));
        Path otherType = vmPackage(config(false, "alpine.bmp"));

        assertEquals(Optional.empty(), iconOf(traversal, utmctlInApp()));
        assertEquals(Optional.empty(), iconOf(otherType, utmctlInApp()));
    }

    @Test
    void builtInSymbolicLinkLeavingUtmsIconFolderIsRejected() throws IOException {
        Path icons = utmctlInApp().getParent().getParent().resolve("Resources/Icons");
        Path outside = Files.write(tempDirectory.resolve("outside.png"), new byte[] {1});
        Files.createSymbolicLink(icons.resolve("alpine.png"), outside);
        Path pkg = vmPackage(config(false, "alpine"));

        assertEquals(Optional.empty(), iconOf(pkg, utmctlInApp()));
    }

    @Test
    void noUtmIconFolderMeansNoBuiltInIcon() throws IOException {
        Path plainCli = Files.createDirectories(tempDirectory.resolve("plain")).resolve("utmctl");
        Files.writeString(plainCli, "#!/bin/sh");
        Path pkg = vmPackage(config(false, "alpine"));

        assertEquals(Optional.empty(), iconOf(pkg, plainCli));
    }

    @Test
    void packageWithoutConfigurationHasNoIcon() throws IOException {
        Path pkg = tempDirectory.resolve("Empty.utm");
        Files.createDirectories(pkg);

        assertFalse(iconOf(pkg, utmctlInApp()).isPresent());
    }

    /** The UTM application as installed on macOS: MacOS/utmctl and Resources/Icons. */
    private Path utmctlInApp() throws IOException {
        Path macOs = Files.createDirectories(tempDirectory.resolve("Applications/UTM.app/Contents/MacOS"));
        Path utmctl = macOs.resolve("utmctl");

        if (!Files.exists(utmctl)) {
            Files.writeString(utmctl, "#!/bin/sh");
        }

        Files.createDirectories(tempDirectory.resolve("Applications/UTM.app/Contents/Resources/Icons"));
        return utmctl;
    }

    private Path builtIn(String fileName) throws IOException {
        Path icons = Files.createDirectories(tempDirectory.resolve("Applications/UTM.app/Contents/Resources/Icons"));
        return Files.write(icons.resolve(fileName), new byte[] {1});
    }

    private Optional<Path> iconOf(Path pkg, Path utmctl) {
        return provider(utmctl, null).iconFile(vm(pkg));
    }

    private UtmVmProvider provider(Path utmctl, Path iconsDirectory) {
        UtmProperties.Icons icons = iconsDirectory == null ? null : new UtmProperties.Icons(iconsDirectory);

        return new UtmVmProvider(new UtmProperties(tempDirectory, ".utm", utmctl, icons));
    }

    private static LocalVm vm(Path pkg) {
        return new LocalVm(pkg.getFileName().toString(), "Omarchy Mac", List.of(pkg));
    }

    /** A VM package with its configuration and its Data folder. */
    private Path vmPackage(String configuration) throws IOException {
        Path pkg = tempDirectory.resolve("vms/Omarchy Mac.utm");
        Files.createDirectories(pkg.resolve("Data"));
        Files.writeString(pkg.resolve("config.plist"), configuration);
        return pkg;
    }

    /**
     * The fixture configuration with the icon settings replaced. A null name removes the
     * {@code Icon} entry.
     */
    private static String config(boolean custom, String icon) {
        String text = UtmConfigFixture.config("Omarchy Mac", UUID)
                .replaceFirst("<key>IconCustom</key>\\s*<(true|false)/>",
                        "<key>IconCustom</key>\\n\\t\\t<" + custom + "/>");

        if (icon == null) {
            return text.replaceFirst("\\t\\t<key>Icon</key>\\s*<string>[^<]*</string>\\n", "");
        }

        return text.replaceFirst("<key>Icon</key>\\s*<string>[^<]*</string>",
                "<key>Icon</key>\\n\\t\\t<string>" + icon + "</string>");
    }
}
