package de.fjordkommission.utmbackup.storage;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Contents of {@code UTM-BACKUP-MANAGER-INFO.properties} at the backup root.
 * {@code formatVersion} is the storage compatibility contract and is the only
 * field that decides whether this application may write. The application version
 * fields are informational and are never used for compatibility decisions.
 *
 * @param formatVersion       highest batch format written to this medium
 * @param applicationName     informational application name, may be empty
 * @param createdAt           when the medium was first marked, offset-aware
 * @param createdByVersion    informational application version that created the marker
 * @param lastWrittenByVersion informational application version that last wrote the marker
 */
public record BackupRootMetadata(
        int formatVersion,
        String applicationName,
        OffsetDateTime createdAt,
        String createdByVersion,
        String lastWrittenByVersion
) {

    public BackupRootMetadata {
        if (formatVersion < BackupFormat.LEGACY) {
            throw new IllegalArgumentException("Storage format version must be at least 1: " + formatVersion);
        }

        Objects.requireNonNull(createdAt, "createdAt");
        applicationName = Objects.requireNonNullElse(applicationName, "");
        createdByVersion = Objects.requireNonNullElse(createdByVersion, "");
        lastWrittenByVersion = Objects.requireNonNullElse(lastWrittenByVersion, "");
    }
}
