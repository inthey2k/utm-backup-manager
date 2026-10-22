package de.fjordkommission.utmbackup.config;

import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides the effective application configuration for read-only display
 * in the user interface.
 */
@Component
public class ApplicationConfigurationView {

    private final BackupProperties backupProperties;
    private final VmProviderRegistry vmProviderRegistry;
    private final SettingsRepository settingsRepository;

    public ApplicationConfigurationView(
            BackupProperties backupProperties,
            VmProviderRegistry vmProviderRegistry,
            SettingsRepository settingsRepository
    ) {
        this.backupProperties = backupProperties;
        this.vmProviderRegistry = vmProviderRegistry;
        this.settingsRepository = settingsRepository;
    }

    public Configuration current() {
        List<ProviderConfiguration> providers = vmProviderRegistry.enabledProviders().stream()
                .map(ProviderConfiguration::from)
                .toList();

        return new Configuration(
                providers,
                backupProperties.root(),
                backupProperties.metadataDb(),
                backupProperties.legacyStateFile(),
                settingsRepository.getRetentionLimit()
        );
    }


    /**
     * @param providers enabled providers in configured order
     */
    public record Configuration(
            List<ProviderConfiguration> providers,
            Path backupRoot,
            Path metadataDb,
            Path legacyStateFile,
            int retentionLimit
    ) {

        /** Display names of all enabled providers, for the page title. */
        public String providerName() {
            return providers.stream()
                    .map(ProviderConfiguration::providerName)
                    .collect(Collectors.joining(", "));
        }
    }

    /**
     * The identity and read-only settings of one enabled provider.
     */
    public record ProviderConfiguration(
            String providerId,
            String providerName,
            List<VmProvider.ConfigurationEntry> entries
    ) {

        static ProviderConfiguration from(VmProvider provider) {
            return new ProviderConfiguration(
                    provider.providerId(),
                    provider.displayName(),
                    provider.configuration()
            );
        }

        public String configurationValue(String key) {
            return entries.stream()
                    .filter(entry -> entry.key().equals(key))
                    .map(VmProvider.ConfigurationEntry::value)
                    .findFirst()
                    .orElse(null);
        }
    }
}
