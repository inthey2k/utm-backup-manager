package de.fjordkommission.utmbackup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Global VM provider selection.
 * {@code providers} holds {@code vm.providers}, and {@code provider} holds the
 * deprecated {@code vm.provider}. Both are null when absent. The effective list is
 * resolved by {@link de.fjordkommission.utmbackup.provider.EnabledVmProviders}.
 * Each provider binds its own settings, for example {@code vm.utm.*} in
 * {@link de.fjordkommission.utmbackup.provider.utm.UtmProperties}, so this class
 * never needs to change when a provider is added.
 */
@ConfigurationProperties("vm")
public record VmProperties(
        List<String> providers,
        String provider
) {

    public VmProperties {
        provider = provider == null ? null : provider.trim();
    }
}
