package de.fjordkommission.utmbackup.model;

import java.util.List;
import java.util.Set;

/**
 * Contains the VM-oriented data required by the backup overview.
 * Retention information is kept separately because it applies across
 * the individual backup entries of each VM. {@code providers} carries the
 * per-provider results, including errors, so the interface can report them.
 * {@code batchIssues} lists batches that were not shown, with their reasons.
 */
public record BackupOverview(
        List<VmOverview> vms,
        Set<VmId> retentionExceeded,
        Set<String> retentionCandidatesForDeletion,
        int retentionLimit,
        List<ProviderSnapshot> providers,
        List<BackupBatchIssue> batchIssues
) {

    /** Whether the provider is enabled, that is, present in this scan's provider results. */
    public boolean providerEnabled(String providerId) {
        return providers.stream()
                .anyMatch(provider -> provider.providerId().equals(providerId));
    }

    /** Whether the provider is enabled, listed its VMs and reported no errors. */
    public boolean providerHealthy(String providerId) {
        return providers.stream()
                .anyMatch(provider -> provider.providerId().equals(providerId)
                        && provider.listed()
                        && !provider.hasErrors());
    }

    /** Whether the batch has an issue. Its backups are still shown unless they cannot be read. */
    public boolean hasBatchIssue(String batchDirectoryName) {
        return batchIssues.stream()
                .anyMatch(issue -> issue.batchDirectoryName().equals(batchDirectoryName));
    }
}
