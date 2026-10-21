package de.fjordkommission.utmbackup.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

import static de.fjordkommission.utmbackup.storage.BackupStorageMetadataReader.*;

/**
 * Writes {@code UTM-BACKUP-MANAGER-INFO.properties} atomically.
 *
 * <p>The text is written to a temporary file in the backup root and then moved over
 * the target, so an interrupted write never leaves a partial marker. The writer
 * never replaces a marker that declares a newer format or that cannot be read, and
 * it never repairs one. Output is deterministic: no timestamps or comments are added
 * beyond the metadata itself, so identical metadata produces identical bytes.
 *
 * <p>Callers must consult {@link BackupStorageGuard} first. The writer also
 * re-checks the existing marker, so it is protected even when called directly.
 */
public final class BackupStorageMetadataWriter {

    private final BackupStorageMetadataReader reader = new BackupStorageMetadataReader();

    public void write(Path root, BackupRootMetadata metadata) {
        if (metadata.formatVersion() > BackupFormat.CURRENT) {
            throw new IllegalArgumentException(
                    "Cannot write storage format " + metadata.formatVersion()
                            + "; this application supports up to " + BackupFormat.CURRENT
            );
        }

        requirePlainValues(metadata);
        requireReplaceable(reader.read(root));

        String text = render(metadata);
        Path target = root.resolve(FILE_NAME);
        Path temporary = null;

        try {
            temporary = Files.createTempFile(root, ".UTM-BACKUP-MANAGER-INFO-", ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            AtomicFiles.replace(temporary, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Root metadata could not be written: " + target, e);
        } finally {
            AtomicFiles.deleteQuietly(temporary);
        }
    }

    private static void requireReplaceable(BackupStorageMetadataResult existing) {
        if (existing instanceof BackupStorageMetadataResult.UnsupportedVersion(int version)) {
            throw new IllegalStateException(
                    "Refusing to replace root metadata of newer storage format " + version
            );
        }

        if (existing instanceof BackupStorageMetadataResult.Invalid(String reason)) {
            throw new IllegalStateException(
                    "Refusing to replace root metadata that cannot be read: " + reason
            );
        }
    }

    /** Values are written one per line and read back trimmed, so they must not contain line breaks or edge spaces. */
    private static void requirePlainValues(BackupRootMetadata metadata) {
        requirePlain(metadata.applicationName(), APPLICATION_NAME);
        requirePlain(metadata.createdByVersion(), CREATED_BY_VERSION);
        requirePlain(metadata.lastWrittenByVersion(), LAST_WRITTEN_BY_VERSION);
    }

    private static void requirePlain(String value, String key) {
        if (value.contains("\n") || value.contains("\r") || !value.equals(value.strip())) {
            throw new IllegalArgumentException(key + " must not contain line breaks or surrounding spaces");
        }
    }

    /** Fixed property order, so the output is deterministic. */
    static String render(BackupRootMetadata metadata) {
        return FORMAT_VERSION + "=" + metadata.formatVersion() + "\n"
                + APPLICATION_NAME + "=" + metadata.applicationName() + "\n"
                + CREATED_AT + "=" + metadata.createdAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n"
                + CREATED_BY_VERSION + "=" + metadata.createdByVersion() + "\n"
                + LAST_WRITTEN_BY_VERSION + "=" + metadata.lastWrittenByVersion() + "\n";
    }
}
