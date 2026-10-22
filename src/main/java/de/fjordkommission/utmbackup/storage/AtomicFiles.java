package de.fjordkommission.utmbackup.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a file by moving a fully written temporary file over it.
 */
final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * The target is either the old file or the complete new one. Some file systems
     * cannot rename atomically; the plain replacement is then used.
     */
    static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes a temporary file if it still exists. A leftover is harmless: it is not a batch. */
    static void deleteQuietly(Path temporary) {
        if (temporary == null) {
            return;
        }

        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // Nothing else refers to the temporary file.
        }
    }
}
