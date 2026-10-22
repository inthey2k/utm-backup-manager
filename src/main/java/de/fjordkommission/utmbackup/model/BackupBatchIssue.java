package de.fjordkommission.utmbackup.model;

/**
 * A problem with a backup batch, shown with its reason. A batch that is not shown
 * contributes no VMs, backups or retention decisions, so an unreadable or unsupported
 * manifest can never lead to a deletion. A problem with a backup's state file is
 * reported here too, while that backup stays protected as Stable.
 *
 * @param batchDirectoryName name of the batch directory
 * @param reason             human-readable explanation
 */
public record BackupBatchIssue(String batchDirectoryName, String reason) {
}
