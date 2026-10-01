package de.fjordkommission.utmbackup.config;

import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Provides the effective application configuration for read-only display
 * in the user interface.
 */
@Component
public class ApplicationConfigurationView {

    private final BackupProperties backupProperties;
    private final VmProperties vmProperties;
    private final VmProviderRegistry vmProviderRegistry;
    private final SettingsRepository settingsRepository;

    public ApplicationConfigurationView(
            BackupProperties backupProperties,
            VmProperties vmProperties,
            VmProviderRegistry vmProviderRegistry,
            SettingsRepository settingsRepository
    ) {
        this.backupProperties = backupProperties;
        this.vmProperties = vmProperties;
        this.vmProviderRegistry = vmProviderRegistry;
        this.settingsRepository = settingsRepository;
    }

    public Configuration current() {
        VmProvider provider = vmProviderRegistry.current();

        return new Configuration(
                provider.providerId(),
                provider.displayName(),
                localVmDirectory(),
                vmProperties.utm().packageSuffix(),
                vmProperties.utm().cli(),
                backupProperties.root(),
                backupProperties.metadataDb(),
                backupProperties.legacyStateFile(),
                settingsRepository.getRetentionLimit()
        );
    }

    private Path localVmDirectory() {
        if (vmProperties.provider().trim().equalsIgnoreCase("utm")) {
            return vmProperties.utm().directory();
        } else {
            return null;
        }
    }


    public record Configuration(
            String providerId,
            String providerName,
            Path localVmDirectory,
            String packageSuffix,
            Path providerCli,
            Path backupRoot,
            Path metadataDb,
            Path legacyStateFile,
            int retentionLimit
    ) {
    }
}