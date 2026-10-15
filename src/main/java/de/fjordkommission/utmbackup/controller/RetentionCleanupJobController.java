package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.RetentionCleanupJob;
import de.fjordkommission.utmbackup.service.RetentionCleanupJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class RetentionCleanupJobController {

    private final RetentionCleanupJobService retentionCleanupJobService;

    public RetentionCleanupJobController(
            RetentionCleanupJobService retentionCleanupJobService
    ) {
        this.retentionCleanupJobService = retentionCleanupJobService;
    }

    @GetMapping("/retention-cleanup/status")
    RetentionCleanupJobResponse retentionCleanupStatus() {
        return retentionCleanupJobService.currentJob()
                .map(RetentionCleanupJobResponse::from)
                .orElseGet(RetentionCleanupJobResponse::idle);
    }

    record RetentionCleanupJobResponse(
            String status,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage
    ) {
        static RetentionCleanupJobResponse from(
                RetentionCleanupJob job
        ) {
            return new RetentionCleanupJobResponse(
                    job.status().name(),
                    job.startedAt(),
                    job.finishedAt(),
                    job.errorMessage()
            );
        }

        static RetentionCleanupJobResponse idle() {
            return new RetentionCleanupJobResponse(
                    "IDLE",
                    null,
                    null,
                    null
            );
        }
    }
}