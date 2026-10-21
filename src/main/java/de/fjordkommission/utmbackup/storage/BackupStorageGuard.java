package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.config.BackupProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Central write guard for the backup medium.
 * Every operation that changes the backup directory or the metadata that describes
 * it calls {@link #assertWritable()} before its first change. The check runs on each
 * call, so a medium changed by another application instance is still detected.
 * Read-only operations do not consult the guard.
 */
@Component
public class BackupStorageGuard {

    static final String APPLICATION_NAME = "UTM Backup Manager";

    private final Path root;
    private final BackupStorageEvaluator evaluator = new BackupStorageEvaluator();
    private final BackupStorageMetadataWriter metadataWriter = new BackupStorageMetadataWriter();
    private final BackupStorageMetadataReader metadataReader = new BackupStorageMetadataReader();

    public BackupStorageGuard(BackupProperties backupProperties) {
        this.root = backupProperties.root();
    }

    public BackupStorageStatus status() {
        return evaluator.evaluate(root);
    }

    public BackupStorageCompatibility compatibility() {
        return status().compatibility();
    }

    public boolean isWritable() {
        return status().isWritable();
    }

    /**
     * @throws BackupStorageReadOnlyException if the medium must not be modified
     */
    public void assertWritable() {
        BackupStorageStatus status = status();

        if (!status.isWritable()) {
            throw new BackupStorageReadOnlyException(status);
        }
    }

    /**
     * Makes sure the marker declares format 2 before a format-2 batch is published.
     *
     * <ul>
     *   <li>No marker: created with the given application version.</li>
     *   <li>Supported marker already at format 2 and last written by this version:
     *       unchanged, so routine backups do not rewrite the file.</li>
     *   <li>Other supported marker: rewritten with the creation fields kept and
     *       {@code last.written.by.version} updated. Application versions stay
     *       informational.</li>
     *   <li>Newer or invalid marker: refused, never overwritten.</li>
     * </ul>
     */
    public void ensureFormatTwoMarker(String applicationVersion) {
        assertWritable();

        OffsetDateTime now = OffsetDateTime.now(ZoneId.systemDefault());

        switch (metadataReader.read(root)) {
            case BackupStorageMetadataResult.Absent() -> writeRootMetadata(new BackupRootMetadata(
                    BackupFormat.PROVIDER_AWARE, APPLICATION_NAME, now, applicationVersion, applicationVersion));
            case BackupStorageMetadataResult.Present(BackupRootMetadata current)
                    when current.formatVersion() == BackupFormat.PROVIDER_AWARE
                    && current.lastWrittenByVersion().equals(applicationVersion) -> {
                // Already declares format 2 and was last written by this version.
            }
            case BackupStorageMetadataResult.Present(BackupRootMetadata current) -> writeRootMetadata(new BackupRootMetadata(
                    BackupFormat.PROVIDER_AWARE, APPLICATION_NAME, current.createdAt(),
                    current.createdByVersion(), applicationVersion));
            case BackupStorageMetadataResult.UnsupportedVersion(int version) -> throw new IllegalStateException(
                    "Refusing to update root metadata of newer storage format " + version);
            case BackupStorageMetadataResult.Invalid(String reason) -> throw new IllegalStateException(
                    "Refusing to update root metadata that cannot be read: " + reason);
        }
    }

    /**
     * Writes the root marker. Production code reaches it only through
     * {@link #ensureFormatTwoMarker(String)}.
     */
    public void writeRootMetadata(BackupRootMetadata metadata) {
        assertWritable();
        metadataWriter.write(root, metadata);
    }
}
