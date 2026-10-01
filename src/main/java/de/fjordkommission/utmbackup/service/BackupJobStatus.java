package de.fjordkommission.utmbackup.service;

/**
 * Lifecycle states of a background backup job.
 */
public enum BackupJobStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
