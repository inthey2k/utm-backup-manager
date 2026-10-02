package de.fjordkommission.utmbackup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Configuration of the active virtualization provider.
 */
@ConfigurationProperties("vm")
public record VmProperties(
        @DefaultValue("utm") String provider,
        Utm utm
) {

    public VmProperties {
        provider = provider == null ? null : provider.trim();
    }

    public record Utm(
            Path directory,
            @DefaultValue(".utm") String packageSuffix,
            Path cli
    ) {

        public Utm {
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
}