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

    public BackupProperties {
        root = trim(root);
        metadataDb = trim(metadataDb);
        legacyStateFile = trim(legacyStateFile);
    }

    /**
     * Removes accidental leading or trailing whitespace from configured paths.
     */
    private static Path trim(Path path) {
        if (path == null) {
            return null;
        }

        return Path.of(path.toString().trim());
    }
}