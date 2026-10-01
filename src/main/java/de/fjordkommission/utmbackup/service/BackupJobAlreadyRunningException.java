package de.fjordkommission.utmbackup.service;

/**
 * Raised when a backup is requested while another backup job is still active.
 */
public class BackupJobAlreadyRunningException extends RuntimeException {

    public BackupJobAlreadyRunningException() {
        super("A backup job is already running");
    }
}
