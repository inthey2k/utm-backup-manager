package de.fjordkommission.utmbackup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Configuration of the backup storage and its persistent metadata.
 */
@ConfigurationProperties("backup")
public record BackupProperties(
        Path root,
        Path metadataDb,
        Path legacyStateFile
) {
}