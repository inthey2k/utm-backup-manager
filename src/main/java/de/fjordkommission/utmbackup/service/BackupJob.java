package de.fjordkommission.utmbackup.service;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of the most recently started backup job.
 */
public record BackupJob(
        BackupJobStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String backupDirectoryName,
        String errorMessage,
        long copiedBytes,
        long totalBytes,
        String currentVmName,
        int currentVmNumber,
        int vmCount
) {
    /**
     * Calculates the progress from the byte counters so stored state cannot diverge.
     */
    public int progressPercent() {
        if (totalBytes <= 0) {
            return 0;
        }

        return (int) Math.min(100, copiedBytes * 100 / totalBytes);
    }
}
