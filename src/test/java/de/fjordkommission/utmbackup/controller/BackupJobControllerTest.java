package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.service.BackupJob;
import de.fjordkommission.utmbackup.service.BackupJobService;
import de.fjordkommission.utmbackup.service.BackupJobStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupJobControllerTest {

    @Test
    void backupStatusIncludesProviderBadgesAlongsideVmNames() {
        BackupJobService backupJobService =
                mock(BackupJobService.class);

        BackupJob job = new BackupJob(
                BackupJobStatus.RUNNING,
                Instant.parse("2026-11-07T16:00:00Z"),
                null,
                null,
                null,
                0,
                100,
                null,
                0,
                2
        );

        when(backupJobService.currentJob())
                .thenReturn(Optional.of(job));

        when(backupJobService.currentVmNames())
                .thenReturn(List.of(
                        "Linux Mint 27",
                        "FreeBSD"
                ));

        when(backupJobService.currentVmProviderBadges())
                .thenReturn(List.of(
                        "UTM",
                        "QEMU"
                ));

        when(backupJobService.currentRetentionPendingDeletionIds())
                .thenReturn(Set.of());

        BackupJobController controller =
                new BackupJobController(backupJobService);

        BackupJobController.BackupJobResponse response =
                controller.backupStatus();

        assertEquals(
                List.of("Linux Mint 27", "FreeBSD"),
                response.vmNames()
        );

        assertEquals(
                List.of("UTM", "QEMU"),
                response.vmProviderBadges()
        );
    }

    @Test
    void idleStatusContainsNoProviderBadges() {
        BackupJobService backupJobService =
                mock(BackupJobService.class);

        when(backupJobService.currentJob())
                .thenReturn(Optional.empty());

        BackupJobController controller =
                new BackupJobController(backupJobService);

        BackupJobController.BackupJobResponse response =
                controller.backupStatus();

        assertEquals(List.of(), response.vmNames());
        assertEquals(List.of(), response.vmProviderBadges());
    }
}