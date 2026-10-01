package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupJobConfiguration;
import de.fjordkommission.utmbackup.model.LocalVm;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts VM backups in the background and exposes their current lifecycle state.
 *
 * Exactly one backup job may run at a time. Completed and failed jobs remain
 * available as the latest snapshot so the web UI can report their outcome.
 */
@Service
public class BackupJobService {

    private final CreateBackupService createBackupService;
    private final Executor backupTaskExecutor;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private final Object jobLock = new Object();
    private volatile BackupJob currentJob;
    private volatile List<String> currentVmNames = List.of();

    public BackupJobService(
            CreateBackupService createBackupService,
            @Qualifier(BackupJobConfiguration.BACKUP_TASK_EXECUTOR)
            Executor backupTaskExecutor
    ) {
        this.createBackupService = createBackupService;
        this.backupTaskExecutor = backupTaskExecutor;
    }

    /**
     * Starts a backup job and returns immediately after the task was accepted.
     *
     * A second request is rejected while the current job is still running.
     */
    public BackupJob start(List<LocalVm> vms, String comment) {
        List<LocalVm> selectedVms = List.copyOf(vms);
        currentVmNames = selectedVms.stream()
                .map(LocalVm::name)
                .toList();
        BackupJob runningJob;

        synchronized (jobLock) {
            if (currentJob != null && currentJob.status() == BackupJobStatus.RUNNING) {
                throw new BackupJobAlreadyRunningException();
            }
            // init the cancellation flag
            cancellationRequested.set(false);

            runningJob = new BackupJob(
                    BackupJobStatus.RUNNING,
                    LocalDateTime.now(),
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    0,
                    selectedVms.size()
            );
            currentJob = runningJob;

            try {
                backupTaskExecutor.execute(
                        () -> runBackup(selectedVms, comment, runningJob.startedAt())
                );
            } catch (RuntimeException e) {
                currentJob = null;
                throw e;
            }
        }

        return runningJob;
    }

    /**
     * Returns the latest job snapshot, or an empty result before the first job.
     */
    public Optional<BackupJob> currentJob() {
        return Optional.ofNullable(currentJob);
    }

    /**
     * Requests cooperative cancellation of the currently running backup.
     */
    public void cancel() {
        synchronized (jobLock) {
            if (currentJob == null
                    || currentJob.status() != BackupJobStatus.RUNNING) {
                throw new IllegalStateException(
                        "No backup is currently running."
                );
            }

            cancellationRequested.set(true);
        }
    }

    /**
     * Returns the VM names belonging to the latest backup job.
     */
    public List<String> currentVmNames() {
        return currentVmNames;
    }

    private void runBackup(
            List<LocalVm> vms,
            String comment,
            LocalDateTime startedAt
    ) {
        try {
            CreateBackupService.Result result = createBackupService.create(
                    vms,
                    comment,
                    progress -> updateProgress(startedAt, progress),
                    cancellationRequested::get
            );

            BackupJob latest = currentJob;
            long totalBytes = latest != null ? latest.totalBytes() : 0;

            currentJob = new BackupJob(
                    BackupJobStatus.COMPLETED,
                    startedAt,
                    LocalDateTime.now(),
                    result.backupDirectoryName(),
                    null,
                    totalBytes,
                    totalBytes,
                    latest != null ? latest.currentVmName() : null,
                    vms.size(),
                    vms.size()
            );
        } catch (BackupCancelledException e) {
            BackupJob latest = currentJob;

            currentJob = new BackupJob(
                    BackupJobStatus.CANCELLED,
                    startedAt,
                    LocalDateTime.now(),
                    null,
                    null,
                    latest != null ? latest.copiedBytes() : 0,
                    latest != null ? latest.totalBytes() : 0,
                    latest != null ? latest.currentVmName() : null,
                    latest != null ? latest.currentVmNumber() : 0,
                    vms.size()
            );
        } catch (RuntimeException e) {
            BackupJob latest = currentJob;

            currentJob = new BackupJob(
                    BackupJobStatus.FAILED,
                    startedAt,
                    LocalDateTime.now(),
                    null,
                    errorMessage(e),
                    latest != null ? latest.copiedBytes() : 0,
                    latest != null ? latest.totalBytes() : 0,
                    latest != null ? latest.currentVmName() : null,
                    latest != null ? latest.currentVmNumber() : 0,
                    vms.size()
            );
        }
    }

    private void updateProgress(
            LocalDateTime startedAt,
            CreateBackupService.Progress progress
    ) {
        BackupJob latest = currentJob;

        // Ignore stale callbacks if a future implementation ever replaces a job.
        if (latest == null
                || latest.status() != BackupJobStatus.RUNNING
                || !latest.startedAt().equals(startedAt)) {
            return;
        }

        currentJob = new BackupJob(
                BackupJobStatus.RUNNING,
                startedAt,
                null,
                null,
                null,
                progress.copiedBytes(),
                progress.totalBytes(),
                progress.currentVmName(),
                progress.currentVmNumber(),
                progress.vmCount()
        );
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
