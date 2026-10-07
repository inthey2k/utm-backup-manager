package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    @Mock
    private BackupScanner scanner;

    @Mock
    private BackupService backupService;

    private RetentionService retentionService;

    @BeforeEach
    void setUp() {
        retentionService = new RetentionService(
                scanner,
                backupService
        );
    }

    @Test
    void vmBelowRetentionLimitDoesNotRequireCleanup() {
        LocalVm localVm = localVm("vm-1", "VM 1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        List<LocalVm> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(localVm)
                );

        assertEquals(List.of(), result);
    }

    @Test
    void vmAtRetentionLimitRequiresCleanup() {
        LocalVm localVm = localVm("vm-1", "VM 1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        List<LocalVm> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(localVm)
                );

        assertEquals(List.of(localVm), result);
    }

    @Test
    void stableBackupsDoNotCountTowardsRetentionLimit() {
        LocalVm localVm = localVm("vm-1", "VM 1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                stableBackup("backup-3", 3)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        List<LocalVm> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(localVm)
                );

        assertEquals(List.of(), result);
    }

    @Test
    void onlySelectedVmsAreReportedForCleanup() {
        LocalVm selectedVm = localVm("vm-1", "VM 1");

        VmOverview selected = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1)
        );

        VmOverview notSelected = vmOverview(
                "vm-2",
                normalBackup("backup-2", 1),
                normalBackup("backup-3", 2),
                normalBackup("backup-4", 3)
        );

        BackupOverview backupOverview = overview(selected, notSelected);
        when(scanner.scan()).thenReturn(backupOverview);

        List<LocalVm> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(selectedVm)
                );

        assertEquals(List.of(), result);
    }

    @Test
    void cleanupDeletesOldestNormalBackup() {
        LocalVm localVm = localVm("vm-1", "VM 1");

        VmBackup oldest = normalBackup("backup-1", 1);
        VmBackup second = normalBackup("backup-2", 2);
        VmBackup newest = normalBackup("backup-3", 3);
        VmBackup newBackup = normalBackup("backup-4", 4);

        VmOverview vm = vmOverview(
                "vm-1",
                oldest,
                second,
                newest,
                newBackup
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        retentionService.deleteBackupsExceedingRetention(
                List.of(localVm)
        );

        verify(backupService).delete("backup-1");
        verify(backupService, never()).delete("backup-2");
        verify(backupService, never()).delete("backup-3");
        verify(backupService, never()).delete("backup-4");
    }

    @Test
    void cleanupDeletesAllNormalBackupsExceedingLimit() {
        LocalVm localVm = localVm("vm-1", "VM 1");

        VmBackup oldest = normalBackup("backup-1", 1);
        VmBackup second = normalBackup("backup-2", 2);
        VmBackup third = normalBackup("backup-3", 3);
        VmBackup fourth = normalBackup("backup-4", 4);
        VmBackup fifth = normalBackup("backup-5", 5);
        VmBackup newest = normalBackup("backup-6", 6);

        VmOverview vm = vmOverview(
                "vm-1",
                oldest,
                second,
                third,
                fourth,
                fifth,
                newest
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        retentionService.deleteBackupsExceedingRetention(
                List.of(localVm)
        );

        verify(backupService).delete("backup-1");
        verify(backupService).delete("backup-2");
        verify(backupService).delete("backup-3");

        verify(backupService, never()).delete("backup-4");
        verify(backupService, never()).delete("backup-5");
        verify(backupService, never()).delete("backup-6");
    }

    @Test
    void cleanupNeverDeletesStableBackups() {
        LocalVm localVm = localVm("vm-1", "VM 1");

        VmBackup oldestNormal = normalBackup("backup-1", 1);
        VmBackup stable = stableBackup("stable-1", 2);
        VmBackup normal2 = normalBackup("backup-2", 3);
        VmBackup normal3 = normalBackup("backup-3", 4);
        VmBackup normal4 = normalBackup("backup-4", 5);

        VmOverview vm = vmOverview(
                "vm-1",
                oldestNormal,
                stable,
                normal2,
                normal3,
                normal4
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        retentionService.deleteBackupsExceedingRetention(
                List.of(localVm)
        );

        verify(backupService).delete("backup-1");
        verify(backupService, never()).delete("stable-1");
        verify(backupService, never()).delete("backup-2");
        verify(backupService, never()).delete("backup-3");
        verify(backupService, never()).delete("backup-4");
    }

    @Test
    void cleanupDoesNothingWhenRetentionLimitIsNotExceeded() {
        LocalVm localVm = localVm("vm-1", "VM 1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        retentionService.deleteBackupsExceedingRetention(
                List.of(localVm)
        );

        verify(backupService, never()).delete(
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    private LocalVm localVm(String id, String name) {
        return new LocalVm(
                id,
                name,
                List.of()
        );
    }

    private VmOverview vmOverview(
            String id,
            VmBackup... backups
    ) {
        LocalVm localVm = localVm(id, id);

        return new VmOverview(
                id,
                id,
                localVm,
                List.of(backups),
                VmStatus.STOPPED,
                VmChangeStatus.UNKNOWN
        );
    }

    private VmBackup normalBackup(String id, int day) {
        return backup(id, day, false);
    }

    private VmBackup stableBackup(String id, int day) {
        return backup(id, day, true);
    }

    private VmBackup backup(
            String id,
            int day,
            boolean stable
    ) {
        return new VmBackup(
                id,
                "2026-01-%02d_1200".formatted(day),
                "vm-1",
                Path.of("/tmp", id),
                LocalDateTime.of(2026, 1, day, 12, 0),
                "",
                stable,
                null
        );
    }

    private BackupOverview overview(
            VmOverview... vms
    ) {
        return new BackupOverview(
                List.of(vms),
                Set.of(),
                Set.of(),
                3
        );
    }}