package de.fjordkommission.utmbackup.storage;

/**
 * Whether this application may read and write a backup medium.
 * Only {@link #LEGACY} and {@link #SUPPORTED} allow writes. Every other state is
 * read-only, and an invalid or newer root is never treated as legacy.
 */
public enum BackupStorageCompatibility {

    /** No root metadata and no format-2 batch: the 1.4.x medium, writable as before. */
    LEGACY(true),

    /** Root metadata with a format version this application understands. */
    SUPPORTED(true),

    /** Root metadata declares a newer storage format than this application supports. */
    READ_ONLY_NEWER_FORMAT(false),

    /** Root metadata exists but cannot be interpreted safely. */
    READ_ONLY_INVALID_METADATA(false),

    /** No root metadata, yet a batch uses a format this application must not treat as legacy. */
    READ_ONLY_INCONSISTENT(false),

    /** The backup directory itself is not available. */
    UNAVAILABLE(false);

    private final boolean writable;

    BackupStorageCompatibility(boolean writable) {
        this.writable = writable;
    }

    public boolean isWritable() {
        return writable;
    }
}
