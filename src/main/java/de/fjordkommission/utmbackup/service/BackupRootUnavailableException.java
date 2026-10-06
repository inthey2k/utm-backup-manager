package de.fjordkommission.utmbackup.service;

import java.io.Serial;
import java.nio.file.Path;

public class BackupRootUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    private final transient Path root;

    public BackupRootUnavailableException(Path root) {
        super("Backup directory is not available: " + root);
        this.root = root;
    }

    public Path root() {
        return root;
    }
}