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

    public record Utm(
            Path directory,
            @DefaultValue(".utm") String packageSuffix,
            Path cli
    ) {
    }
}