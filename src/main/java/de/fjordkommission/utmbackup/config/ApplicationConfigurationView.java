package de.fjordkommission.utmbackup.config;

import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

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
        VmProvider provider = vmProviderRegistry.current();

        return new Configuration(
                provider.providerId(),
                provider.displayName(),
                provider.configuration(),
                backupProperties.root(),
                backupProperties.metadataDb(),
                backupProperties.legacyStateFile(),
                settingsRepository.getRetentionLimit()
        );
    }


    public record Configuration(
            String providerId,
            String providerName,
            List<VmProvider.ConfigurationEntry> providerConfiguration,
            Path backupRoot,
            Path metadataDb,
            Path legacyStateFile,
            int retentionLimit
    ) {

        public String providerConfigurationValue(String key) {
            return providerConfiguration.stream()
                    .filter(entry -> entry.key().equals(key))
                    .map(VmProvider.ConfigurationEntry::value)
                    .findFirst()
                    .orElse(null);
        }
    }
}