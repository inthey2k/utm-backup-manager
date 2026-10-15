package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.RetentionCleanupJob;
import de.fjordkommission.utmbackup.model.RetentionCleanupJobStatus;
import de.fjordkommission.utmbackup.service.RetentionCleanupJobService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetentionCleanupJobControllerTest {

    private final RetentionCleanupJobService retentionCleanupJobService =
            mock(RetentionCleanupJobService.class);

    private final RetentionCleanupJobController controller =
            new RetentionCleanupJobController(retentionCleanupJobService);

    @Test
    void returnsIdleWhenNoCleanupJobExists() {
        when(retentionCleanupJobService.currentJob())
                .thenReturn(Optional.empty());

        RetentionCleanupJobController.RetentionCleanupJobResponse response =
                controller.retentionCleanupStatus();

        assertEquals("IDLE", response.status());
        assertNull(response.startedAt());
        assertNull(response.finishedAt());
        assertNull(response.errorMessage());
    }

    @Test
    void returnsCurrentCleanupJob() {
        Instant startedAt = Instant.parse("2026-10-15T18:00:00Z");
        Instant finishedAt = Instant.parse("2026-10-15T18:01:00Z");

        RetentionCleanupJob job = new RetentionCleanupJob(
                RetentionCleanupJobStatus.COMPLETED,
                startedAt,
                finishedAt,
                null
        );

        when(retentionCleanupJobService.currentJob())
                .thenReturn(Optional.of(job));

        RetentionCleanupJobController.RetentionCleanupJobResponse response =
                controller.retentionCleanupStatus();

        assertEquals("COMPLETED", response.status());
        assertEquals(startedAt, response.startedAt());
        assertEquals(finishedAt, response.finishedAt());
        assertNull(response.errorMessage());
    }

    @Test
    void returnsCleanupErrorMessage() {
        Instant startedAt = Instant.parse("2026-10-15T18:00:00Z");
        Instant finishedAt = Instant.parse("2026-10-15T18:01:00Z");

        RetentionCleanupJob job = new RetentionCleanupJob(
                RetentionCleanupJobStatus.FAILED,
                startedAt,
                finishedAt,
                "Cleanup failed"
        );

        when(retentionCleanupJobService.currentJob())
                .thenReturn(Optional.of(job));

        RetentionCleanupJobController.RetentionCleanupJobResponse response =
                controller.retentionCleanupStatus();

        assertEquals("FAILED", response.status());
        assertEquals("Cleanup failed", response.errorMessage());
    }
}