package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.*;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    private static final String PROVIDER = "utm";

    @Mock
    private BackupScanner scanner;

    @Mock
    private BackupService backupService;

    @Mock
    private VmIdentityService vmIdentityService;

    private BackupStorageGuard storageGuard;

    private RetentionService retentionService;

    @BeforeEach
    void setUp() {
        storageGuard = mock(BackupStorageGuard.class);
        retentionService = new RetentionService(
                scanner,
                backupService,
                storageGuard,
                vmIdentityService
        );
    }

    @Test
    void vmBelowRetentionLimitDoesNotRequireCleanup() {
        useIdentityPassThrough();

        VmId vmId = vmId("vm-1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        List<VmId> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(vmId)
                );

        assertEquals(List.of(), result);
    }

    @Test
    void vmAtRetentionLimitRequiresCleanup() {
        useIdentityPassThrough();

        VmId vmId = vmId("vm-1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        List<VmId> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(vmId)
                );

        assertEquals(List.of(vmId), result);
    }

    @Test
    void stableBackupsDoNotCountTowardsRetentionLimit() {
        useIdentityPassThrough();

        VmId vmId = vmId("vm-1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                stableBackup("backup-3", 3)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        List<VmId> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(vmId)
                );

        assertEquals(List.of(), result);
    }

    @Test
    void onlySelectedVmsAreReportedForCleanup() {
        useIdentityPassThrough();

        VmId selectedVm = vmId("vm-1");

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

        List<VmId> result =
                retentionService.findVmsRequiringRetentionCleanup(
                        List.of(selectedVm)
                );

        assertEquals(List.of(), result);
    }

    @Test
    void sameLocalIdInAnotherProviderIsNotAffectedByRetention() {
        useIdentityPassThrough();

        // A qemu VM with the same local id must not inherit utm retention decisions.
        VmId utmVm = new VmId("utm", "ArchLinux");
        VmId qemuVm = new VmId("qemu", "ArchLinux");

        VmOverview utmOverview = vmOverview(
                "utm", "ArchLinux",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3)
        );
        VmOverview qemuOverview = vmOverview(
                "qemu", "ArchLinux"
        );

        when(scanner.scan()).thenReturn(overview(utmOverview, qemuOverview));

        assertEquals(List.of(utmVm), retentionService.findVmsRequiringRetentionCleanup(
                List.of(utmVm, qemuVm)
        ));
    }

    @Test
    void cleanupDeletesOldestNormalBackup() {
        useIdentityPassThrough();
        VmId vmId = vmId("vm-1");

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
                List.of(vmId)
        );

        verify(backupService).delete("backup-1");
        verify(backupService, never()).delete("backup-2");
        verify(backupService, never()).delete("backup-3");
        verify(backupService, never()).delete("backup-4");
    }

    @Test
    void cleanupDeletesAllNormalBackupsExceedingLimit() {
        useIdentityPassThrough();
        VmId vmId = vmId("vm-1");

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
                List.of(vmId)
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
        useIdentityPassThrough();
        VmId vmId = vmId("vm-1");

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
                List.of(vmId)
        );

        verify(backupService).delete("backup-1");
        verify(backupService, never()).delete("stable-1");
        verify(backupService, never()).delete("backup-2");
        verify(backupService, never()).delete("backup-3");
        verify(backupService, never()).delete("backup-4");
    }

    @Test
    void cleanupDoesNothingWhenRetentionLimitIsNotExceeded() {
        useIdentityPassThrough();
        VmId vmId = vmId("vm-1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3)
        );

        BackupOverview backupOverview = overview(vm);
        when(scanner.scan()).thenReturn(backupOverview);

        retentionService.deleteBackupsExceedingRetention(
                List.of(vmId)
        );

        verify(backupService, never()).delete(
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void findsBackupsPendingDeletion() {
        VmId vmId = vmId("vm-1");

        VmBackup oldest = normalBackup("backup-1", 1);
        VmBackup second = normalBackup("backup-2", 2);
        VmBackup third = normalBackup("backup-3", 3);
        VmBackup newest = normalBackup("backup-4", 4);

        VmOverview vm = vmOverview(
                "vm-1",
                oldest,
                second,
                third,
                newest
        );

        when(scanner.scan()).thenReturn(overview(vm));

        Set<String> result =
                retentionService.findBackupsPendingDeletion(
                        List.of(vmId)
                );

        assertEquals(
                Set.of("backup-1", "backup-2"),
                result
        );
    }

    @Test
    void pendingDeletionNeverIncludesStableBackups() {
        VmId vmId = vmId("vm-1");

        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                stableBackup("stable-1", 2),
                normalBackup("backup-2", 3),
                normalBackup("backup-3", 4),
                normalBackup("backup-4", 5)
        );

        when(scanner.scan()).thenReturn(overview(vm));

        Set<String> result =
                retentionService.findBackupsPendingDeletion(
                        List.of(vmId)
                );

        assertEquals(
                Set.of("backup-1", "backup-2"),
                result
        );
    }

    // ------------------------------------------------------------------
    // Current deletion candidates (manual cleanup) versus pending after the next backup
    // ------------------------------------------------------------------

    @Test
    void currentCandidatesAreTheOldestNormalBackupsBeyondTheLimit() {
        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3),
                normalBackup("backup-4", 4),
                normalBackup("backup-5", 5)
        );
        when(scanner.scan()).thenReturn(overview(vm));

        assertEquals(List.of("backup-1", "backup-2"), retentionService.findCurrentDeletionCandidates());
    }

    @Test
    void backupsOnlyPendingAfterTheNextBackupAreNotCurrentCandidates() {
        // At the limit: nothing is a candidate now, but the next backup would make the oldest one one.
        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3)
        );
        when(scanner.scan()).thenReturn(overview(vm));

        assertEquals(List.of(), retentionService.findCurrentDeletionCandidates());
        assertEquals(Set.of("backup-1"), retentionService.findBackupsPendingDeletion(List.of(vmId("vm-1"))));
    }

    @Test
    void stableBackupsAreNeverCurrentCandidates() {
        VmOverview vm = vmOverview(
                "vm-1",
                stableBackup("stable-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3),
                normalBackup("backup-4", 4),
                normalBackup("backup-5", 5)
        );
        when(scanner.scan()).thenReturn(overview(vm));

        // Four normal backups, limit three: the oldest normal backup is the candidate, not the older Stable one.
        assertEquals(List.of("backup-2"), retentionService.findCurrentDeletionCandidates());
    }

    @Test
    void mergedCanonicalHistoryIsEvaluatedAsOneVm() {
        // The scanner merges the histories of one VM under its canonical id, so the limit counts across them.
        VmOverview merged = vmOverview(
                "canonical-1",
                normalBackup("legacy-a", 1),
                normalBackup("legacy-b", 2),
                normalBackup("backup-3", 3),
                normalBackup("backup-4", 4)
        );
        when(scanner.scan()).thenReturn(overview(merged));

        assertEquals(List.of("legacy-a"), retentionService.findCurrentDeletionCandidates());
    }

    @Test
    void currentCandidatesAreCalculatedPerVm() {
        VmOverview first = vmOverview(
                "vm-1",
                normalBackup("a-1", 1),
                normalBackup("a-2", 2),
                normalBackup("a-3", 3),
                normalBackup("a-4", 4)
        );
        VmOverview second = vmOverview(
                "vm-2",
                normalBackup("b-1", 1),
                normalBackup("b-2", 2)
        );
        VmOverview third = vmOverview(
                "vm-3",
                normalBackup("c-1", 1),
                normalBackup("c-2", 2),
                normalBackup("c-3", 3),
                normalBackup("c-4", 4),
                normalBackup("c-5", 5)
        );
        when(scanner.scan()).thenReturn(overview(first, second, third));

        assertEquals(List.of("a-1", "c-1", "c-2"), retentionService.findCurrentDeletionCandidates());
    }

    @Test
    void noCurrentCandidatesDeletesNothing() {
        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2)
        );
        when(scanner.scan()).thenReturn(overview(vm));

        assertEquals(0, retentionService.deleteCurrentDeletionCandidates());

        verify(backupService, never()).deleteAll(anyCollection());
        verify(backupService, never()).delete(anyString());
    }

    @Test
    void deleteCurrentCandidatesHandsTheCandidatesToTheValidatedBulkDeletion() {
        VmOverview vm = vmOverview(
                "vm-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3),
                normalBackup("backup-4", 4)
        );
        when(scanner.scan()).thenReturn(overview(vm));
        when(backupService.deleteAll(List.of("backup-1"))).thenReturn(1);

        assertEquals(1, retentionService.deleteCurrentDeletionCandidates());

        verify(storageGuard).assertWritable();
        verify(backupService).deleteAll(List.of("backup-1"));
        verify(backupService, never()).delete(anyString());
    }

    @Test
    void readOnlyStorageStopsCurrentCleanupBeforeAnythingIsLooked() {
        doThrow(new IllegalStateException("read-only")).when(storageGuard).assertWritable();

        assertThrows(IllegalStateException.class, () -> retentionService.deleteCurrentDeletionCandidates());

        verify(scanner, never()).scan();
        verify(backupService, never()).deleteAll(anyCollection());
    }

    // ------------------------------------------------------------------
    // Canonical ids: a local (legacy) id must resolve to the canonical history
    // ------------------------------------------------------------------

    @Test
    void legacyLocalIdOfAMergedVmIsResolvedForAutomaticCleanup() {
        VmId legacy = vmId("Old Name.utm");
        VmId canonical = vmId("canonical-1");
        when(vmIdentityService.resolveCanonicalId(legacy)).thenReturn(canonical);

        VmOverview vm = vmOverview(
                "canonical-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3),
                normalBackup("backup-4", 4)
        );
        when(scanner.scan()).thenReturn(overview(vm));

        retentionService.deleteBackupsExceedingRetention(List.of(legacy));

        verify(backupService).delete("backup-1");
        verify(backupService, never()).delete("backup-2");
    }

    @Test
    void legacyLocalIdOfAMergedVmRequiresCleanupUnderItsCanonicalId() {
        VmId legacy = vmId("Old Name.utm");
        VmId canonical = vmId("canonical-1");
        useIdentityPassThrough();
        when(vmIdentityService.resolveCanonicalId(legacy)).thenReturn(canonical);

        VmOverview vm = vmOverview(
                "canonical-1",
                normalBackup("backup-1", 1),
                normalBackup("backup-2", 2),
                normalBackup("backup-3", 3),
                normalBackup("backup-4", 4)
        );
        when(scanner.scan()).thenReturn(overview(vm));

        assertEquals(List.of(canonical), retentionService.findVmsRequiringRetentionCleanup(List.of(legacy)));
    }

    private void useIdentityPassThrough() {
        when(vmIdentityService.resolveCanonicalId(any(VmId.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static VmId vmId(String localId) {
        return new VmId(PROVIDER, localId);
    }

    private VmOverview vmOverview(
            String localId,
            VmBackup... backups
    ) {
        return vmOverview(PROVIDER, localId, backups);
    }

    private VmOverview vmOverview(
            String providerId,
            String localId,
            VmBackup... backups
    ) {
        VmId id = new VmId(providerId, localId);
        LocalVm localVm = new LocalVm(localId, localId, List.of());

        return new VmOverview(
                id,
                providerId,
                localId,
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
                3,
                List.of(),
                List.of()
        );
    }
}