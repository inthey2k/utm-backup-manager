package de.fjordkommission.utmbackup.storage;

/**
 * The compatibility state of a backup medium together with the human-readable
 * reason, which the UI can show later.
 */
public record BackupStorageStatus(BackupStorageCompatibility compatibility, String reason) {

    public boolean isWritable() {
        return compatibility.isWritable();
    }
}
