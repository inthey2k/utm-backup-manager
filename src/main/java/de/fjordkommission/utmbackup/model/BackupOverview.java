package de.fjordkommission.utmbackup.model;

import java.util.List;
import java.util.Set;

/**
 * Contains the VM-oriented data required by the backup overview.
 *
 * Retention information is kept separately because it applies across
 * the individual backup entries of each VM.
 */
public record BackupOverview(
        List<VmOverview> vms,
        Set<String> retentionExceeded,
        Set<String> retentionCandidatesForDeletion,
        int retentionLimit
) {
}