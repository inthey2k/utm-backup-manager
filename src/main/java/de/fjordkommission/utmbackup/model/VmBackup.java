package de.fjordkommission.utmbackup.model;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * One backup of a VM as shown on its card.
 *
 * @param sizeBytes total size of the backup's files, or null when it could not be read
 */
public record VmBackup(
        String id,
        String backupDirectoryName,
        String vmName,
        Path vmPath,
        LocalDateTime backupTime,
        String comment,
        boolean stable,
        String note,
        Long sizeBytes
) {

    /** A backup whose size is not known, for example in tests that do not need it. */
    public VmBackup(
            String id,
            String backupDirectoryName,
            String vmName,
            Path vmPath,
            LocalDateTime backupTime,
            String comment,
            boolean stable,
            String note
    ) {
        this(id, backupDirectoryName, vmName, vmPath, backupTime, comment, stable, note, null);
    }
}
