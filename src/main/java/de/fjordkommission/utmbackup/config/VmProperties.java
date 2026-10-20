package de.fjordkommission.utmbackup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration of the VM providers.
 *
 * {@code providers} holds {@code vm.providers}, and {@code provider} holds the
 * deprecated {@code vm.provider}. Both are null when absent. The effective list is
 * resolved by {@link de.fjordkommission.utmbackup.provider.EnabledVmProviders}.
 * Provider-specific settings stay in their own namespace, for example
 * {@code vm.utm.*}.
 */
@ConfigurationProperties("vm")
public record VmProperties(
        List<String> providers,
        String provider,
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
