package de.fjordkommission.utmbackup.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the {@code BACKUP-INFO.txt} of one batch directory.
 *
 * A missing file is a format-1 batch with an empty comment, as in 1.4.x. A file
 * that exists but cannot be read is {@link BackupManifestResult.Invalid}: its
 * format cannot be known, so it must not be shown as legacy. Bytes that are not
 * valid UTF-8 are replaced rather than rejected, because 1.4.x tolerated them and
 * the format markers are ASCII.
 */
public final class BackupManifestReader {

    public static final String FILE_NAME = "BACKUP-INFO.txt";

    private final BackupManifestParser parser = new BackupManifestParser();

    public BackupManifestResult read(Path batchDirectory) {
        Path file = batchDirectory.resolve(FILE_NAME);

        if (!Files.isRegularFile(file)) {
            return new BackupManifestResult.Parsed(BackupManifest.legacy(""));
        }

        try {
            return parser.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new BackupManifestResult.Invalid(FILE_NAME + " could not be read: " + e.getMessage());
        }
    }
}
