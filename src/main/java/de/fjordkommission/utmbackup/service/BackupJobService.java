package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupJobConfiguration;
import de.fjordkommission.utmbackup.model.LocalVm;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
                    Instant.now(),
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
            Instant startedAt
    ) {
        try {
            CreateBackupService.Result result = createBackupService.create(
                    vms,
                    comment,
                    progress -> updateProgress(startedAt, progress),
                    cancellationRequested::get
            );

            completeJob(startedAt, result, vms.size());

        } catch (BackupCancelledException e) {
            cancelJob(startedAt, vms.size());

        } catch (RuntimeException e) {
            failJob(startedAt, vms.size(), e);
        }
    }
    private void completeJob(
            Instant startedAt,
            CreateBackupService.Result result,
            int vmCount
    ) {
        BackupJob latest = currentJob;
        long totalBytes = latest != null ? latest.totalBytes() : 0;

        currentJob = new BackupJob(
                BackupJobStatus.COMPLETED,
                startedAt,
                Instant.now(),
                result.backupDirectoryName(),
                null,
                totalBytes,
                totalBytes,
                latest != null ? latest.currentVmName() : null,
                vmCount,
                vmCount
        );
    }
    private void cancelJob(Instant startedAt, int vmCount) {
        BackupJob latest = currentJob;

        currentJob = new BackupJob(
                BackupJobStatus.CANCELLED,
                startedAt,
                Instant.now(),
                null,
                null,
                copiedBytes(latest),
                totalBytes(latest),
                currentVmName(latest),
                currentVmNumber(latest),
                vmCount
        );
    }
    private void failJob(
            Instant startedAt,
            int vmCount,
            RuntimeException exception
    ) {
        BackupJob latest = currentJob;

        currentJob = new BackupJob(
                BackupJobStatus.FAILED,
                startedAt,
                Instant.now(),
                null,
                errorMessage(exception),
                copiedBytes(latest),
                totalBytes(latest),
                currentVmName(latest),
                currentVmNumber(latest),
                vmCount
        );
    }
    private long copiedBytes(BackupJob job) {
        return job != null ? job.copiedBytes() : 0;
    }

    private long totalBytes(BackupJob job) {
        return job != null ? job.totalBytes() : 0;
    }

    private String currentVmName(BackupJob job) {
        return job != null ? job.currentVmName() : null;
    }

    private int currentVmNumber(BackupJob job) {
        return job != null ? job.currentVmNumber() : 0;
    }

    private void updateProgress(
            Instant startedAt,
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
