package de.fjordkommission.utmbackup.model;

/**
 * A backup batch that the overview does not show, with the reason.
 *
 * Such a batch contributes no VMs, backups or retention decisions, so an
 * unreadable or unsupported manifest can never lead to a deletion.
 *
 * @param batchDirectoryName name of the batch directory
 * @param reason             human-readable explanation
 */
public record BackupBatchIssue(String batchDirectoryName, String reason) {
}
