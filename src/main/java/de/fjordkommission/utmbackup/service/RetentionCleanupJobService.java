package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupJobConfiguration;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.RetentionCleanupJob;
import de.fjordkommission.utmbackup.model.RetentionCleanupJobStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

@Service
public class RetentionCleanupJobService {

    private final RetentionService retentionService;
    private final Executor cleanupTaskExecutor;

    private final Object jobLock = new Object();
    private RetentionCleanupJob currentJob;

    public RetentionCleanupJobService(
            RetentionService retentionService,
            @Qualifier(BackupJobConfiguration.RETENTION_CLEANUP_TASK_EXECUTOR)
            Executor cleanupTaskExecutor
    ) {
        this.retentionService = retentionService;
        this.cleanupTaskExecutor = cleanupTaskExecutor;
    }

    /**
     * Starts retention cleanup in the background.
     */
    public void start(List<LocalVm> vms) {
        List<LocalVm> selectedVms = List.copyOf(vms);
        RetentionCleanupJob runningJob;

        synchronized (jobLock) {
            if (currentJob != null
                    && currentJob.status() == RetentionCleanupJobStatus.RUNNING) {
                throw new IllegalStateException(
                        "Retention cleanup is already running."
                );
            }

            Instant startedAt = Instant.now();

            runningJob = new RetentionCleanupJob(
                    RetentionCleanupJobStatus.RUNNING,
                    startedAt,
                    null,
                    null
            );

            currentJob = runningJob;

            try {
                cleanupTaskExecutor.execute(
                        () -> runCleanup(selectedVms, startedAt)
                );
            } catch (RuntimeException e) {
                currentJob = null;
                throw e;
            }
        }
    }

    public Optional<RetentionCleanupJob> currentJob() {
        synchronized (jobLock) {
            return Optional.ofNullable(currentJob);
        }
    }

    private void runCleanup(
            List<LocalVm> vms,
            Instant startedAt
    ) {
        try {
            retentionService.deleteBackupsExceedingRetention(vms);

            updateJob(
                    startedAt,
                    RetentionCleanupJobStatus.COMPLETED,
                    null
            );
        } catch (RuntimeException e) {
            updateJob(
                    startedAt,
                    RetentionCleanupJobStatus.FAILED,
                    errorMessage(e)
            );
        }
    }

    private void updateJob(
            Instant startedAt,
            RetentionCleanupJobStatus status,
            String errorMessage
    ) {
        synchronized (jobLock) {
            if (currentJob == null
                    || currentJob.status() != RetentionCleanupJobStatus.RUNNING
                    || !currentJob.startedAt().equals(startedAt)) {
                return;
            }

            currentJob = new RetentionCleanupJob(
                    status,
                    startedAt,
                    Instant.now(),
                    errorMessage
            );
        }
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();

        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}