package de.fjordkommission.utmbackup.storage;

/**
 * Raised when a mutating operation is attempted on a read-only backup medium.
 * The message is the domain reason, so existing error paths that show exception
 * messages report it without further changes.
 */
public final class BackupStorageReadOnlyException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient BackupStorageStatus status;

    public BackupStorageReadOnlyException(BackupStorageStatus status) {
        super("Backup storage is read-only: " + status.reason());
        this.status = status;
    }

    public BackupStorageStatus status() {
        return status;
    }
}
