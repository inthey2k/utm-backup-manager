package de.fjordkommission.utmbackup.provider.utm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the image file of a UTM VM icon.
 *
 * <p>A custom icon is stored in the VM package: {@code IconCustom} is true and {@code Icon} names
 * a file in {@code <package>/Data/} on macOS. A built-in icon is {@code IconCustom} false, and
 * {@code Icon} names one of UTM's own images, for example {@code alpine}. Those images are not
 * copied: they are read from UTM's installed {@code Resources/Icons} folder, which is derived from
 * the real path of {@code utmctl}, so Homebrew symbolic links are followed. An explicit directory
 * setting takes precedence.
 *
 * <p>Every file must be a regular file of a supported image type within the size limit, and its
 * real path must lie inside the trusted root: the package for a custom icon, UTM's icon folder for
 * a built-in icon. Otherwise the result is empty and the caller uses the fallback icon.
 */
public final class UtmIcons {

    /** Folder of a custom icon image inside the VM package. */
    static final String CUSTOM_ICON_FOLDER = "Data";

    /** A built-in icon name without an extension refers to a PNG image. */
    static final String DEFAULT_BUILT_IN_SUFFIX = ".png";

    /** A custom icon is a small image. Larger files are ignored rather than served. */
    static final long MAX_ICON_BYTES = 2L * 1024 * 1024;

    private static final List<String> SUPPORTED_SUFFIXES = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    private UtmIcons() {
    }

    /**
     * Returns the real path of UTM's built-in icon folder. An explicit directory is used when
     * given; otherwise the folder is {@code Contents/Resources/Icons} of the application that
     * contains the real {@code utmctl} file, which is {@code Contents/MacOS/utmctl}. Empty when
     * neither exists.
     */
    public static Optional<Path> builtInIconDirectory(Path utmctl, Path configuredDirectory) {
        if (configuredDirectory != null) {
            return existingDirectory(configuredDirectory);
        }

        if (utmctl == null) {
            return Optional.empty();
        }

        try {
            Path real = utmctl.toRealPath();
            Path macOs = real.getParent();
            Path contents = macOs == null ? null : macOs.getParent();

            if (contents == null) {
                return Optional.empty();
            }

            return existingDirectory(contents.resolve("Resources").resolve("Icons"));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the image file that the icon setting of a package names, or empty when the icon
     * is not usable.
     *
     * @param packageDirectory   the {@code .utm} package of the VM
     * @param setting            the icon settings read from its {@code config.plist}
     * @param builtInDirectory   UTM's built-in icon folder, if it was found
     */
    public static Optional<Path> resolve(
            Path packageDirectory,
            UtmPackageIdentity.IconSetting setting,
            Optional<Path> builtInDirectory
    ) {
        if (setting == null || setting.name() == null || !isPlainName(setting.name())) {
            return Optional.empty();
        }

        try {
            if (setting.custom()) {
                String fileName = imageFileName(setting.name(), true);

                if (fileName == null) {
                    return Optional.empty();
                }

                return usableImage(
                        packageDirectory.resolve(CUSTOM_ICON_FOLDER).resolve(fileName),
                        packageDirectory.toRealPath()
                );
            }

            Optional<Path> folder = builtInDirectory;
            String fileName = imageFileName(setting.name(), false);

            if (folder.isEmpty() || fileName == null) {
                return Optional.empty();
            }

            return usableImage(folder.get().resolve(fileName), folder.get());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The image file name for an icon name. A custom icon must name its file with a supported
     * suffix. A built-in name without a dot gets the default suffix, and a name with another
     * suffix is rejected.
     */
    private static String imageFileName(String name, boolean custom) {
        if (hasSupportedSuffix(name)) {
            return name;
        }

        if (custom || name.contains(".")) {
            return null;
        }

        return name + DEFAULT_BUILT_IN_SUFFIX;
    }

    private static boolean hasSupportedSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SUPPORTED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /** A bare file name: no separators, and not a relative reference such as "." or "..". */
    private static boolean isPlainName(String name) {
        return !name.isBlank()
                && !name.contains("/")
                && !name.contains("\\")
                && !name.equals(".")
                && !name.equals("..");
    }

    /**
     * A regular image file, within the size limit, whose real path lies below the trusted root.
     * Following symbolic links before the comparison keeps a link from leading out of the root.
     */
    private static Optional<Path> usableImage(Path candidate, Path trustedRoot) throws IOException {
        if (!Files.isRegularFile(candidate) || Files.size(candidate) > MAX_ICON_BYTES) {
            return Optional.empty();
        }

        Path real = candidate.toRealPath();

        return real.startsWith(trustedRoot.toRealPath()) ? Optional.of(real) : Optional.empty();
    }

    private static Optional<Path> existingDirectory(Path directory) {
        try {
            return Files.isDirectory(directory) ? Optional.of(directory.toRealPath()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
