package de.fjordkommission.utmbackup.model;

import java.util.List;
import java.util.Set;

/**
 * Contains the VM-oriented data required by the backup overview.
 *
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
}
