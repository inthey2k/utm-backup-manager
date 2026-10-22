package de.fjordkommission.utmbackup.provider.qemu;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * QEMU provider settings, bound from {@code vm.qemu.*}. Registered by
 * {@link QemuProviderConfiguration} only when QEMU is enabled.
 * Both values are optional at binding time. A missing directory is reported by
 * the provider as a provider error, so it never prevents other providers from
 * starting.
 *
 * @param directory directory that contains one subdirectory per QEMU VM
 */
@ConfigurationProperties("vm.qemu")
public record QemuProperties(Path directory) {

    public QemuProperties {
        directory = trim(directory);
    }

    private static Path trim(Path path) {
        if (path == null) {
            return null;
        }

        return Path.of(path.toString().trim());
    }
}
