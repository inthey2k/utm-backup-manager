package de.fjordkommission.utmbackup.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents a locally available VM and the files or directories that belong
 * to its backup.
 *
 * The provider decides which sources belong to a VM. The backup manager treats
 * these sources independently of the underlying hypervisor.
 *
 * The ID is a stable, provider-defined technical identifier used to associate
 * a local VM with its backups and persisted state. It must remain stable across
 * application restarts.
 */
public record LocalVm(
        String id,
        String name,
        List<Path> backupSources
) {

    public LocalVm {
        backupSources = List.copyOf(backupSources);
    }

}