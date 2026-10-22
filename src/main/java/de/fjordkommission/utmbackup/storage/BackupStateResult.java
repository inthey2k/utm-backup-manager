package de.fjordkommission.utmbackup.storage;

/**
 * Outcome of reading a format-2 state file. An unreadable file is never reported as
 * {@link Absent}, because absence means the defaults apply.
 */
public sealed interface BackupStateResult {

    /** No state file: Stable is false and the note is empty. */
    record Absent() implements BackupStateResult {
    }

    /** The state file was understood. */
    record Parsed(BackupState state) implements BackupStateResult {
    }

    /** The state file exists but is malformed or unreadable. Its content must not be trusted. */
    record Invalid(String reason) implements BackupStateResult {
    }
}
