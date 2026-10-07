package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.RetentionCleanupJob;
import de.fjordkommission.utmbackup.model.RetentionCleanupJobStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetentionCleanupJobServiceTest {

    private final LocalVm vm = new LocalVm(
            "test-vm",
            "Test VM",
            List.of(Path.of("Test.utm"))
    );

    @Test
    void startsCleanupWithoutRunningItOnCallingThread() {
        RetentionService retentionService = mock(RetentionService.class);
        CapturingExecutor executor = new CapturingExecutor();

        RetentionCleanupJobService service =
                new RetentionCleanupJobService(
                        retentionService,
                        executor
                );

        service.start(List.of(vm));

        RetentionCleanupJob job =
                service.currentJob().orElseThrow();

        assertEquals(
                RetentionCleanupJobStatus.RUNNING,
                job.status()
        );
        assertNotNull(job.startedAt());
        assertNull(job.finishedAt());
        assertNull(job.errorMessage());

        assertNotNull(executor.task());

        verifyNoInteractions(retentionService);
    }

    @Test
    void marksSuccessfulCleanupAsCompleted() {
        RetentionService retentionService = mock(RetentionService.class);
        CapturingExecutor executor = new CapturingExecutor();

        RetentionCleanupJobService service =
                new RetentionCleanupJobService(
                        retentionService,
                        executor
                );

        service.start(List.of(vm));
        executor.runCapturedTask();

        RetentionCleanupJob job =
                service.currentJob().orElseThrow();

        assertEquals(
                RetentionCleanupJobStatus.COMPLETED,
                job.status()
        );
        assertNotNull(job.startedAt());
        assertNotNull(job.finishedAt());
        assertNull(job.errorMessage());

        verify(retentionService)
                .deleteBackupsExceedingRetention(List.of(vm));
    }

    @Test
    void marksFailedCleanupAsFailed() {
        RetentionService retentionService = mock(RetentionService.class);
        CapturingExecutor executor = new CapturingExecutor();

        doThrow(new IllegalStateException("Cleanup failed"))
                .when(retentionService)
                .deleteBackupsExceedingRetention(List.of(vm));

        RetentionCleanupJobService service =
                new RetentionCleanupJobService(
                        retentionService,
                        executor
                );

        service.start(List.of(vm));
        executor.runCapturedTask();

        RetentionCleanupJob job =
                service.currentJob().orElseThrow();

        assertEquals(
                RetentionCleanupJobStatus.FAILED,
                job.status()
        );
        assertNotNull(job.startedAt());
        assertNotNull(job.finishedAt());
        assertEquals("Cleanup failed", job.errorMessage());
    }

    private static final class CapturingExecutor implements Executor {

        private final AtomicReference<Runnable> task =
                new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            if (!task.compareAndSet(null, command)) {
                throw new IllegalStateException(
                        "A task is already captured"
                );
            }
        }

        Runnable task() {
            return task.get();
        }

        void runCapturedTask() {
            Runnable captured = task.getAndSet(null);

            assertNotNull(
                    captured,
                    "No cleanup task was captured"
            );

            captured.run();
        }
    }
}