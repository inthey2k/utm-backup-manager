package de.fjordkommission.utmbackup.storage;

/**
 * Mutable state of one format-2 backup, stored in its container as
 * {@code BACKUP-STATE.properties}. The note is always stripped, so an absent or blank
 * note is the empty string.
 */
public record BackupState(boolean stable, String note) {

    /** The state of a backup without a state file. */
    public static final BackupState DEFAULT = new BackupState(false, "");

    public BackupState {
        note = note == null ? "" : note.strip();
    }
}
