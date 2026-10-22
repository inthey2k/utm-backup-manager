package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.junit.jupiter.api.Test;


import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static de.fjordkommission.utmbackup.TestVms.stoppedUtm;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupJobServiceTest {

    private final LocalVm vm = new LocalVm(
            "test-vm",
            "Test VM",
            List.of(Path.of("Test.utm"))
    );
    private final VmId vmId = new VmId("utm", "test-vm");
    private final RetentionCleanupJobService retentionCleanupJobService =
            mock(RetentionCleanupJobService.class);

    private final RetentionService retentionService =
            mock(RetentionService.class);

    @Test
    void startsBackupWithoutRunningItOnCallingThread() {
        CreateBackupService createBackupService = mock(CreateBackupService.class);
        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        List<ManagedVm> vms = List.of(stoppedUtm(vm));
        BackupJob job = service.start(vms, "Before update");

        assertEquals(BackupJobStatus.RUNNING, job.status());
        assertNull(job.finishedAt());
        assertNull(job.backupDirectoryName());
        assertNull(job.errorMessage());
        assertEquals(0, job.copiedBytes());
        assertEquals(0, job.totalBytes());
        assertNull(job.currentVmName());
        assertEquals(0, job.currentVmNumber());
        assertEquals(1, job.vmCount());
        assertEquals(0, job.progressPercent());
        assertEquals(job, service.currentJob().orElseThrow());
        assertNotNull(executor.task());
        verifyNoInteractions(createBackupService);
    }

    @Test
    void calculatesProgressPercentFromByteCounters() {
        BackupJob job = new BackupJob(
                BackupJobStatus.RUNNING,
                Instant.now(),
                null,
                null,
                null,
                25,
                100,
                "Test VM",
                1,
                1
        );

        assertEquals(25, job.progressPercent());
    }

    @Test
    void rejectsSecondBackupWhileFirstJobIsRunning() {
        CreateBackupService createBackupService = mock(CreateBackupService.class);
        CapturingExecutor executor = new CapturingExecutor();


        BackupJobService service =
                createService(createBackupService, executor);

        List<ManagedVm> vms = List.of(stoppedUtm(vm));
        service.start(vms, null);

        assertThrows(
                BackupJobAlreadyRunningException.class,
                () -> service.start(vms, null)
        );

        verifyNoInteractions(createBackupService);
    }

    @Test
    void updatesRunningJobFromCopyProgress() {
        CreateBackupService createBackupService = mock(CreateBackupService.class);
        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        CreateBackupService.Result result = new CreateBackupService.Result(
                "2026-09-30_1430",
                Path.of("backups/2026-09-30_1430"),
                LocalDateTime.parse("2026-09-30T14:30:00")
        );

        when(createBackupService.create(
                eq(List.of(stoppedUtm(vm))),
                eq("Before update"),
                any(CreateBackupService.ProgressListener.class),
                any(CreateBackupService.CancellationCheck.class)
        )).thenAnswer(invocation -> {
            CreateBackupService.ProgressListener listener =
                    invocation.getArgument(2);

            listener.onProgress(new CreateBackupService.Progress(
                    40,
                    100,
                    "Test VM",
                    1,
                    1
            ));

            BackupJob progressJob = service.currentJob().orElseThrow();

            assertEquals(BackupJobStatus.RUNNING, progressJob.status());
            assertEquals(40, progressJob.copiedBytes());
            assertEquals(100, progressJob.totalBytes());
            assertEquals("Test VM", progressJob.currentVmName());
            assertEquals(1, progressJob.currentVmNumber());
            assertEquals(1, progressJob.vmCount());
            assertEquals(40, progressJob.progressPercent());

            return result;
        });

        List<ManagedVm> vms = List.of(stoppedUtm(vm));
        service.start(vms, "Before update");
        executor.runCapturedTask();

        BackupJob completedJob = service.currentJob().orElseThrow();

        assertEquals(BackupJobStatus.COMPLETED, completedJob.status());
        assertEquals(100, completedJob.copiedBytes());
        assertEquals(100, completedJob.totalBytes());
        assertEquals(100, completedJob.progressPercent());
    }

    @Test
    void marksSuccessfulBackupAsCompleted() {
        CreateBackupService createBackupService = mock(CreateBackupService.class);
        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        CreateBackupService.Result result = new CreateBackupService.Result(
                "2026-09-30_1430",
                Path.of("backups/2026-09-30_1430"),
                LocalDateTime.parse("2026-09-30T14:30:00")
        );

        when(createBackupService.create(
                eq(List.of(stoppedUtm(vm))),
                eq("Before update"),
                any(CreateBackupService.ProgressListener.class),
                any(CreateBackupService.CancellationCheck.class)
        )).thenReturn(result);
        List<ManagedVm> vms = List.of(stoppedUtm(vm));
        BackupJob runningJob = service.start(vms, "Before update");
        executor.runCapturedTask();

        BackupJob completedJob = service.currentJob().orElseThrow();
        assertEquals(BackupJobStatus.COMPLETED, completedJob.status());
        assertEquals(runningJob.startedAt(), completedJob.startedAt());
        assertNotNull(completedJob.finishedAt());
        assertEquals("2026-09-30_1430", completedJob.backupDirectoryName());
        assertNull(completedJob.errorMessage());
    }

    @Test
    void marksFailedBackupAsFailed() {
        CreateBackupService createBackupService = mock(CreateBackupService.class);
        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        List<ManagedVm> vms = List.of(stoppedUtm(vm));
        when(createBackupService.create(
                eq(vms),
                isNull(),
                any(CreateBackupService.ProgressListener.class),
                any(CreateBackupService.CancellationCheck.class)
        )).thenThrow(new IllegalStateException("Backup failed"));

        BackupJob runningJob = service.start(vms, null);
        executor.runCapturedTask();

        BackupJob failedJob = service.currentJob().orElseThrow();
        assertEquals(BackupJobStatus.FAILED, failedJob.status());
        assertEquals(runningJob.startedAt(), failedJob.startedAt());
        assertNotNull(failedJob.finishedAt());
        assertNull(failedJob.backupDirectoryName());
        assertEquals("Backup failed", failedJob.errorMessage());
    }

    @Test
    void marksCancelledBackupAsCancelled() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        List<ManagedVm> vms = List.of(stoppedUtm(vm));
        doAnswer(invocation -> {
            CreateBackupService.CancellationCheck cancellationCheck =
                    invocation.getArgument(3);

            assertTrue(cancellationCheck.isCancellationRequested());

            throw new BackupCancelledException();
        }).when(createBackupService).create(
                eq(vms),
                isNull(),
                any(CreateBackupService.ProgressListener.class),
                any(CreateBackupService.CancellationCheck.class)
        );

        BackupJob runningJob = service.start(vms, null);

        service.cancel();
        executor.runCapturedTask();

        BackupJob cancelledJob = service.currentJob().orElseThrow();

        assertEquals(
                BackupJobStatus.CANCELLED,
                cancelledJob.status()
        );
        assertEquals(
                runningJob.startedAt(),
                cancelledJob.startedAt()
        );
        assertNotNull(cancelledJob.finishedAt());
        assertNull(cancelledJob.backupDirectoryName());
        assertNull(cancelledJob.errorMessage());
    }

    @Test
    void rejectsCancellationWhenNoBackupIsRunning() {
        CreateBackupService createBackupService = mock(CreateBackupService.class);
        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        assertThrows(
                IllegalStateException.class,
                service::cancel
        );
    }

    @Test
    void startsRetentionCleanupAfterSuccessfulBackup() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        CreateBackupService.Result result =
                new CreateBackupService.Result(
                        "2026-10-07_1400",
                        Path.of("backups/2026-10-07_1400"),
                        LocalDateTime.of(2026, 10, 7, 14, 0)
                );

        when(createBackupService.create(
                eq(List.of(stoppedUtm(vm))),
                isNull(),
                any(),
                any()
        )).thenReturn(result);

        service.start(
                List.of(stoppedUtm(vm)),
                null,
                List.of(vmId)
        );

        executor.runCapturedTask();

        assertEquals(
                BackupJobStatus.COMPLETED,
                service.currentJob().orElseThrow().status()
        );

        verify(retentionCleanupJobService).start(List.of(vmId));
    }

    @Test
    void doesNotStartRetentionCleanupAfterFailedBackup() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        when(createBackupService.create(
                eq(List.of(stoppedUtm(vm))),
                isNull(),
                any(),
                any()
        )).thenThrow(new IllegalStateException("Backup failed"));

        service.start(
                List.of(stoppedUtm(vm)),
                null,
                List.of(vmId)
        );

        executor.runCapturedTask();

        assertEquals(
                BackupJobStatus.FAILED,
                service.currentJob().orElseThrow().status()
        );

        verifyNoInteractions(retentionCleanupJobService);
    }

    @Test
    void exposesPendingDeletionIdsWhileBackupIsRunning() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        when(retentionService.findBackupsPendingDeletion(List.of(vmId)))
                .thenReturn(Set.of("backup-1", "backup-2"));

        service.start(
                List.of(stoppedUtm(vm)),
                null,
                List.of(vmId)
        );

        assertEquals(
                Set.of("backup-1", "backup-2"),
                service.currentRetentionPendingDeletionIds()
        );
    }

    @Test
    void clearsPendingDeletionIdsAfterSuccessfulBackup() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        when(retentionService.findBackupsPendingDeletion(List.of(vmId)))
                .thenReturn(Set.of("backup-1"));

        CreateBackupService.Result result =
                new CreateBackupService.Result(
                        "2026-10-15_1800",
                        Path.of("backups/2026-10-15_1800"),
                        LocalDateTime.of(2026, 10, 15, 18, 0)
                );

        when(createBackupService.create(
                eq(List.of(stoppedUtm(vm))),
                isNull(),
                any(),
                any()
        )).thenReturn(result);

        service.start(
                List.of(stoppedUtm(vm)),
                null,
                List.of(vmId)
        );

        assertEquals(
                Set.of("backup-1"),
                service.currentRetentionPendingDeletionIds()
        );

        executor.runCapturedTask();

        assertEquals(
                Set.of(),
                service.currentRetentionPendingDeletionIds()
        );
    }

    @Test
    void clearsPendingDeletionIdsAfterFailedBackup() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        when(retentionService.findBackupsPendingDeletion(List.of(vmId)))
                .thenReturn(Set.of("backup-1"));

        when(createBackupService.create(
                eq(List.of(stoppedUtm(vm))),
                isNull(),
                any(),
                any()
        )).thenThrow(new IllegalStateException("Backup failed"));

        service.start(
                List.of(stoppedUtm(vm)),
                null,
                List.of(vmId)
        );

        executor.runCapturedTask();

        assertEquals(
                Set.of(),
                service.currentRetentionPendingDeletionIds()
        );
    }

    @Test
    void cleanupStartFailureDoesNotChangeSuccessfulBackupResult() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        CreateBackupService.Result result =
                new CreateBackupService.Result(
                        "2026-10-15_1800",
                        Path.of("backups/2026-10-15_1800"),
                        LocalDateTime.of(2026, 10, 15, 18, 0)
                );

        when(createBackupService.create(
                eq(List.of(stoppedUtm(vm))),
                isNull(),
                any(),
                any()
        )).thenReturn(result);

        doThrow(new IllegalStateException("Cleanup could not be started"))
                .when(retentionCleanupJobService)
                .start(List.of(vmId));

        service.start(
                List.of(stoppedUtm(vm)),
                null,
                List.of(vmId)
        );

        executor.runCapturedTask();

        BackupJob job = service.currentJob().orElseThrow();

        assertEquals(BackupJobStatus.COMPLETED, job.status());
        assertNull(job.errorMessage());

        verify(retentionCleanupJobService).start(List.of(vmId));
    }

    @Test
    void clearsPendingDeletionIdsAfterCancelledBackup() {
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                createService(createBackupService, executor);

        when(retentionService.findBackupsPendingDeletion(List.of(vmId)))
                .thenReturn(Set.of("backup-1"));

        doAnswer(invocation -> {
            CreateBackupService.CancellationCheck cancellationCheck =
                    invocation.getArgument(3);

            assertTrue(cancellationCheck.isCancellationRequested());

            throw new BackupCancelledException();
        }).when(createBackupService).create(
                eq(List.of(stoppedUtm(vm))),
                isNull(),
                any(CreateBackupService.ProgressListener.class),
                any(CreateBackupService.CancellationCheck.class)
        );

        service.start(
                List.of(stoppedUtm(vm)),
                null,
                List.of(vmId)
        );

        assertEquals(
                Set.of("backup-1"),
                service.currentRetentionPendingDeletionIds()
        );

        service.cancel();
        executor.runCapturedTask();

        assertEquals(
                BackupJobStatus.CANCELLED,
                service.currentJob().orElseThrow().status()
        );

        assertEquals(
                Set.of(),
                service.currentRetentionPendingDeletionIds()
        );

        verifyNoInteractions(retentionCleanupJobService);
    }

    /**
     * Stores a submitted task so tests can control exactly when it starts.
     */
    private static final class CapturingExecutor implements Executor {

        private final AtomicReference<Runnable> task = new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            if (!task.compareAndSet(null, command)) {
                throw new IllegalStateException("A task is already captured");
            }
        }

        Runnable task() {
            return task.get();
        }

        void runCapturedTask() {
            Runnable captured = task.getAndSet(null);
            assertNotNull(captured, "No backup task was captured");
            captured.run();
        }

    }

    /**
     * Little helper to avoid creating the BackupJobService in EACH test
     */
    private BackupJobService createService(
            CreateBackupService createBackupService,
            Executor executor
    ) {
        return new BackupJobService(
                createBackupService,
                retentionCleanupJobService,
                retentionService,
                mock(BackupStorageGuard.class),
                executor
        );
    }
}
