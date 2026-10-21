package de.fjordkommission.utmbackup.storage;

/**
 * Outcome of reading the root metadata. A missing file and an invalid file are
 * different cases, and a newer or invalid file is never reported as absent.
 */
public sealed interface BackupStorageMetadataResult {

    /** No root metadata file exists. */
    record Absent() implements BackupStorageMetadataResult {
    }

    /** The metadata was read and its format version is known to this application. */
    record Present(BackupRootMetadata metadata) implements BackupStorageMetadataResult {
    }

    /** The metadata declares a format newer than {@link BackupFormat#CURRENT}. */
    record UnsupportedVersion(int formatVersion) implements BackupStorageMetadataResult {
    }

    /** The file exists but cannot be interpreted safely. */
    record Invalid(String reason) implements BackupStorageMetadataResult {
    }
}
