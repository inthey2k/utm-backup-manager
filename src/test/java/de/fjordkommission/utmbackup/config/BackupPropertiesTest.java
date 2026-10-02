package de.fjordkommission.utmbackup.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupPropertiesTest {

    @Test
    void trimsConfiguredPaths() {
        BackupProperties properties = new BackupProperties(
                Path.of(" /tmp/backups "),
                Path.of(" /tmp/metadata.db "),
                Path.of(" /tmp/backup-state.tsv ")
        );

        assertEquals(Path.of("/tmp/backups"), properties.root());
        assertEquals(Path.of("/tmp/metadata.db"), properties.metadataDb());
        assertEquals(Path.of("/tmp/backup-state.tsv"), properties.legacyStateFile());
    }
}