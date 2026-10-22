package de.fjordkommission.utmbackup.model;

import java.util.List;

/**
 * Connects a VM with its existing backup(s) for the overview.
 * The VM is identified by its canonical {@link VmId}. {@code localVm} is null for
 * backup-only VMs, which no enabled provider currently reports. Keeping these
 * entries visible prevents existing backups from disappearing from the interface.
 *
 * @param id           canonical identity of the VM
 * @param providerName display name of the owning provider, or its id when that provider is not enabled
 * @param name         display name of the VM
 * @param localVm      the local VM, or null for backup-only VMs
 * @param backups      backups associated with this VM
 * @param status       runtime state, UNKNOWN for backup-only VMs
 * @param changeStatus change-tracking result
 */
public record VmOverview(
        VmId id,
        String providerName,
        String name,
        LocalVm localVm,
        List<VmBackup> backups,
        VmStatus status,
        VmChangeStatus changeStatus
) {

    public String providerId() {
        return id.providerId();
    }

    /** Provider badge label shown next to the VM name. The canonical id is never shown. */
    public String providerBadge() {
        return VmId.providerBadge(providerId());
    }

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
