package de.fjordkommission.utmbackup.storage;

/**
 * Batch format versions stored in the {@code Format-Version} line of
 * {@code BACKUP-INFO.txt}.
 *
 * These are storage-contract versions, not application versions. A later
 * application release may still write the same format version.
 */
public final class BackupFormat {

    /**
     * Batches without a {@code Format-Version} line: {@code VMs/<folder>} with
     * UTM package folders. Their identity comes from the folder name.
     */
    public static final int LEGACY = 1;

    /**
     * Provider-aware manifest with canonical VM identities and their container
     * directories.
     */
    public static final int PROVIDER_AWARE = 2;

    /** The highest format this application can read and, once enabled, write. */
    public static final int CURRENT = PROVIDER_AWARE;

    private BackupFormat() {
    }
}
