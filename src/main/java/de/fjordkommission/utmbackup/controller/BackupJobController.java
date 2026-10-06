package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.service.BackupJob;
import de.fjordkommission.utmbackup.service.BackupJobService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
public class BackupJobController {

    private final BackupJobService backupJobService;

    public BackupJobController(BackupJobService backupJobService) {
        this.backupJobService = backupJobService;
    }

    @GetMapping("/backup/status")
    BackupJobResponse backupStatus() {
        return backupJobService.currentJob()
                .map(job -> BackupJobResponse.from(
                        job,
                        backupJobService.currentVmNames()
                ))
                .orElseGet(BackupJobResponse::idle);
    }

    @PostMapping("/backup/cancel")
    void cancelBackup() {
        backupJobService.cancel();
    }

    record BackupJobResponse(
            String status,
            String backupDirectoryName,
            String errorMessage,
            long copiedBytes,
            long totalBytes,
            int progressPercent,
            String currentVmName,
            int currentVmNumber,
            int vmCount,
            List<String> vmNames,
            long elapsedSeconds
    ) {
        static BackupJobResponse from(
                BackupJob job,
                List<String> vmNames
        ) {
            return new BackupJobResponse(
                    job.status().name(),
                    job.backupDirectoryName(),
                    job.errorMessage(),
                    job.copiedBytes(),
                    job.totalBytes(),
                    job.progressPercent(),
                    job.currentVmName(),
                    job.currentVmNumber(),
                    job.vmCount(),
                    vmNames,
                    Math.max(
                            0,
                            Duration.between(
                                    job.startedAt(),
                                    job.finishedAt() != null
                                            ? job.finishedAt()
                                            : Instant.now()
                            ).getSeconds()
                    )

            );
        }
        static BackupJobResponse idle() {
            return new BackupJobResponse(
                    "IDLE",
                    null,
                    null,
                    0,
                    0,
                    0,
                    null,
                    0,
                    0,
                    List.of(),
                    0
            );
        }
    }
}