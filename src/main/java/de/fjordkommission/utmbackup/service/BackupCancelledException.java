package de.fjordkommission.utmbackup.service;

/**
 * Signals that a running backup was cancelled intentionally.
 */
public class BackupCancelledException extends RuntimeException {

    public BackupCancelledException() {
        super("Backup was cancelled.");
    }
}