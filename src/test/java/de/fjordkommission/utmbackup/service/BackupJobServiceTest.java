package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupJobServiceTest {

    private final LocalVm vm = new LocalVm(
            "test-vm",
            "Test VM",
            List.of(Path.of("Test.utm"))
    );

    @Test
    void startsBackupWithoutRunningItOnCallingThread() {
        CreateBackupService createBackupService = mock(CreateBackupService.class);
        CapturingExecutor executor = new CapturingExecutor();
        BackupJobService service = new BackupJobService(createBackupService, executor);

        List<LocalVm> vms = List.of(vm);
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
        BackupJobService service = new BackupJobService(createBackupService, executor);

        List<LocalVm> vms = List.of(vm);
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
        BackupJobService service = new BackupJobService(createBackupService, executor);

        CreateBackupService.Result result = new CreateBackupService.Result(
                "2026-09-30_1430",
                Path.of("backups/2026-09-30_1430"),
                LocalDateTime.parse("2026-09-30T14:30:00")
        );

        when(createBackupService.create(
                eq(List.of(vm)),
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

        List<LocalVm> vms = List.of(vm);
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
        BackupJobService service = new BackupJobService(createBackupService, executor);

        CreateBackupService.Result result = new CreateBackupService.Result(
                "2026-09-30_1430",
                Path.of("backups/2026-09-30_1430"),
                LocalDateTime.parse("2026-09-30T14:30:00")
        );

        when(createBackupService.create(
                eq(List.of(vm)),
                eq("Before update"),
                any(CreateBackupService.ProgressListener.class),
                any(CreateBackupService.CancellationCheck.class)
        )).thenReturn(result);
        List<LocalVm> vms = List.of(vm);
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
        BackupJobService service = new BackupJobService(createBackupService, executor);

        List<LocalVm> vms = List.of(vm);
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
                new BackupJobService(createBackupService, executor);
        List<LocalVm> vms = List.of(vm);
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
        CreateBackupService createBackupService =
                mock(CreateBackupService.class);

        CapturingExecutor executor = new CapturingExecutor();

        BackupJobService service =
                new BackupJobService(createBackupService, executor);

        assertThrows(
                IllegalStateException.class,
                service::cancel
        );
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
}
