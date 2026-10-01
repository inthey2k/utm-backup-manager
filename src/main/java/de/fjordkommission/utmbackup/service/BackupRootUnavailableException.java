package de.fjordkommission.utmbackup.service;

import java.nio.file.Path;

public class BackupRootUnavailableException extends RuntimeException {

    private final Path root;

    public BackupRootUnavailableException(Path root) {
        super("Backup directory is not available: " + root);
        this.root = root;
    }

    public Path root() {
        return root;
    }
}