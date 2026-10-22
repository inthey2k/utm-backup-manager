package de.fjordkommission.utmbackup.storage;

/**
 * Outcome of reading a batch manifest. The three cases are kept apart so that
 * no caller can treat an unreadable or newer manifest as format 1.
 */
public sealed interface BackupManifestResult {

    /** The manifest was understood. */
    record Parsed(BackupManifest manifest) implements BackupManifestResult {
    }

    /** The manifest declares a format newer than {@link BackupFormat#CURRENT}. */
    record UnsupportedVersion(int formatVersion) implements BackupManifestResult {
    }

    /** The manifest is malformed or ambiguous. Its content must not be trusted. */
    record Invalid(String reason) implements BackupManifestResult {
    }
}
