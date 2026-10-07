package de.fjordkommission.utmbackup.model;

import java.time.Instant;

public record RetentionCleanupJob(
        RetentionCleanupJobStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {
}