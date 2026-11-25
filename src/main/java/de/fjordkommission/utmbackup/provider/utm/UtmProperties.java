package de.fjordkommission.utmbackup.provider.utm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * UTM provider settings, bound from {@code vm.utm.*}. Registered by
 * {@link UtmProviderConfiguration} only when UTM is enabled.
 * The values may be null at binding time. {@link UtmVmProvider} reports a missing
 * directory or CLI path as a startup error, so the message names the properties.
 *
 * @param directory      directory containing the UTM VM packages
 * @param packageSuffix  suffix of a VM package directory, {@code .utm} by default
 * @param cli            path to the {@code utmctl} command
 * @param icons          optional icon settings, {@code vm.utm.icons.*}; may be null
 */
@ConfigurationProperties("vm.utm")
public record UtmProperties(
        Path directory,
        @DefaultValue(".utm") String packageSuffix,
        Path cli,
        Icons icons
) {

    /**
     * Settings for the built-in UTM icons.
     *
     * @param directory directory of UTM's built-in icon images, overriding the automatic
     *                  resolution from {@code vm.utm.cli}; may be null
     */
    public record Icons(Path directory) {
    }

    public UtmProperties {
        directory = trim(directory);
        packageSuffix = packageSuffix == null
                ? null
                : packageSuffix.trim();
        cli = trim(cli);

        if (icons != null) {
            icons = new Icons(trim(icons.directory()));
        }
    }

    /** The configured icon directory, or null when it is resolved automatically. */
    public Path iconsDirectory() {
        return icons == null ? null : icons.directory();
    }

    private static Path trim(Path path) {
        if (path == null) {
            return null;
        }

        return Path.of(path.toString().trim());
    }
}
