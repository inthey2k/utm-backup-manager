package de.fjordkommission.utmbackup.storage;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Writes {@code BACKUP-STATE.properties} of a format-2 backup container.
 *
 * <p>The output has no timestamp, so the same state always gives the same bytes. It is
 * parsed again before it is written, and a state that would not read back unchanged is
 * refused. The file is written to a temporary file in the container and moved over the
 * target, so a reader sees the old state or the complete new one, never a partial file.
 */
@Component
public final class BackupStateWriter {

    static final String HEADER = "# UTM Backup Manager: mutable state of this backup, replaced atomically.";

    private final BackupStateReader reader = new BackupStateReader();

    /**
     * Returns the UTF-8 text of a state file.
     *
     * @throws IllegalArgumentException if the state would not be read back unchanged
     */
    public String write(BackupState state) {
        String text = HEADER + "\n"
                + BackupStateReader.STABLE_KEY + "=" + state.stable() + "\n"
                + BackupStateReader.NOTE_KEY + "=" + BackupStateReader.escape(state.note()) + "\n";

        if (!(reader.parse(text) instanceof BackupStateResult.Parsed(BackupState readBack))
                || !readBack.equals(state)) {
            throw new IllegalArgumentException("Backup state would not be read back unchanged and was not written");
        }

        return text;
    }

    /**
     * Writes the state file of a container atomically.
     *
     * @throws UncheckedIOException if the file could not be written; the previous file,
     *                              if any, is kept
     */
    public void writeFile(Path container, BackupState state) {
        String text = write(state);
        Path target = container.resolve(BackupStateReader.FILE_NAME);
        Path temporary = null;

        try {
            temporary = Files.createTempFile(container, ".BACKUP-STATE-", ".tmp");
            Files.writeString(temporary, text, UTF_8);
            AtomicFiles.replace(temporary, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Backup state could not be written: " + target, e);
        } finally {
            AtomicFiles.deleteQuietly(temporary);
        }
    }
}
