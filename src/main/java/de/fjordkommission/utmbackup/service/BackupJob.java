package de.fjordkommission.utmbackup.service;

import java.time.Instant;
import java.time.Duration;

import static java.util.Objects.isNull;

/**
 * Immutable snapshot of the most recently started backup job.
 */
public record BackupJob(
        BackupJobStatus status,
        Instant startedAt,
        Instant finishedAt,
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

    /**
     * Returns the total job running time once the job has finished.
     */
    public Duration duration() {
        if (isNull(finishedAt)) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, finishedAt);
    }
}
