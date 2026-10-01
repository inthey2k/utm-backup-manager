package de.fjordkommission.utmbackup.model;

import java.nio.file.Path;
import java.time.LocalDateTime;

public record VmBackup(
        String id,
        String backupDirectoryName,
        String vmName,
        Path vmPath,
        LocalDateTime backupTime,
        String comment,
        boolean stable,
        String note
) {

}