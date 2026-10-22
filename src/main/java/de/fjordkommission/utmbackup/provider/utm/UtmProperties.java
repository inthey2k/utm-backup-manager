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
 */
@ConfigurationProperties("vm.utm")
public record UtmProperties(
        Path directory,
        @DefaultValue(".utm") String packageSuffix,
        Path cli
) {

    public UtmProperties {
        directory = trim(directory);
        packageSuffix = packageSuffix == null
                ? null
                : packageSuffix.trim();
        cli = trim(cli);
    }

    private static Path trim(Path path) {
        if (path == null) {
            return null;
        }

        return Path.of(path.toString().trim());
    }
}
