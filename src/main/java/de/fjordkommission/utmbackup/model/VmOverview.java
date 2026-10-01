package de.fjordkommission.utmbackup.model;

import java.util.List;

/**
 * Connects a local VM with its existing backup(s) for the overview.
 * The local VM is null for VMs that no longer exist locally but still have
 * a backup. Keeping these entries visible prevents existing backups from
 * disappearing from the management interface.
 */
public record VmOverview(
        String id,
        String name,
        LocalVm localVm,
        List<VmBackup> backups,
        VmStatus status,
        VmChangeStatus changeStatus
) {

    public long backupCount() {
        return backups.size();
    }

    public long stableCount() {
        return backups.stream()
                .filter(VmBackup::stable)
                .count();
    }

    public long normalCount() {
        return backupCount() - stableCount();
    }

    public boolean local() {
        return localVm != null;
    }
}