package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RetentionService {

    private final BackupScanner scanner;
    private final BackupService backupService;
    private final BackupStorageGuard storageGuard;
    private final VmIdentityService identityService;

    public RetentionService(
            BackupScanner scanner,
            BackupService backupService,
            BackupStorageGuard storageGuard,
            VmIdentityService identityService
    ) {
        this.scanner = scanner;
        this.backupService = backupService;
        this.storageGuard = storageGuard;
        this.identityService = identityService;
    }

    /**
     * Returns selected VMs for which another normal backup would
     * require a retention cleanup.
     */
    public List<VmId> findVmsRequiringRetentionCleanup(
            Collection<VmId> selectedVms
    ) {
        var overview = scanner.scan();
        int retentionLimit = overview.retentionLimit();

        Set<VmId> affectedVmIds = overview.vms().stream()
                .filter(vm -> normalBackupCount(vm) >= retentionLimit)
                .map(VmOverview::id)
                .map(identityService::resolveCanonicalId)
                .collect(Collectors.toSet());

        return selectedVms.stream()
                .map(identityService::resolveCanonicalId)
                .filter(affectedVmIds::contains)
                .distinct()
                .toList();
    }

    /**
     * Returns the backups that are CURRENTLY deletion candidates: for each VM of the current
     * scan, the oldest normal backups beyond the retention limit. Stable backups are never
     * included. The histories are the canonical VM histories produced by {@link BackupScanner}.
     *
     * <p>This is not the set of {@link #findBackupsPendingDeletion} (which asks what the next
     * backup would make a candidate), so the manual cleanup must not use that calculation.
     */
    public List<String> findCurrentDeletionCandidates() {
        var overview = scanner.scan();

        return overview.vms().stream()
                .flatMap(vm -> currentDeletionCandidates(vm, overview.retentionLimit()).stream())
                .map(VmBackup::id)
                .toList();
    }

    /**
     * Deletes all backups that are currently deletion candidates, through the same validated
     * bulk deletion as the manual selection. Returns how many backups were removed; with no
     * candidates nothing is deleted and 0 is returned.
     */
    public int deleteCurrentDeletionCandidates() {
        storageGuard.assertWritable();

        List<String> candidates = findCurrentDeletionCandidates();

        if (candidates.isEmpty()) {
            return 0;
        }

        return backupService.deleteAll(candidates);
    }

    /**
     * Determine VMs, which would be cleaned up after the current backup job
     * because they are beyond the retention limit.
     *
     * <p>These are the backups that WOULD become deletion candidates after one more backup.
     * They are not the current candidates; see {@link #findCurrentDeletionCandidates}.
     */
    public Set<String> findBackupsPendingDeletion(Collection<VmId> vmIds) {
        var overview = scanner.scan();
        int retentionLimit = overview.retentionLimit();

        Set<String> backupIds = new HashSet<>();

        overview.vms().stream()
                .filter(vm -> vmIds.contains(vm.id()))
                .forEach(vm -> {
                    List<VmBackup> normalBackups = vm.backups().stream()
                            .filter(backup -> !backup.stable())
                            .sorted(Comparator.comparing(VmBackup::backupTime))
                            .toList();

                    int excessAfterBackup =
                            normalBackups.size() + 1 - retentionLimit;

                    normalBackups.stream()
                            .limit(Math.max(0, excessAfterBackup))
                            .map(VmBackup::id)
                            .forEach(backupIds::add);
                });

        return Set.copyOf(backupIds);
    }

    /**
     * Deletes the oldest normal backups until the retention limit
     * is satisfied for the supplied VMs. The supplied ids may be local
     * or canonical; they are resolved to canonical ids before matching.
     */
    public void deleteBackupsExceedingRetention(Collection<VmId> vmIds) {
        storageGuard.assertWritable();

        var overview = scanner.scan();
        int retentionLimit = overview.retentionLimit();

        Set<VmId> canonicalVmIds = vmIds.stream()
                .map(identityService::resolveCanonicalId)
                .collect(Collectors.toSet());

        for (VmOverview vm : overview.vms()) {
            if (!canonicalVmIds.contains(vm.id())) {
                continue;
            }

            currentDeletionCandidates(vm, retentionLimit).stream()
                    .map(VmBackup::id)
                    .forEach(backupService::delete);
        }
    }

    /** The oldest normal backups of one VM that exceed the limit, oldest first. */
    private static List<VmBackup> currentDeletionCandidates(VmOverview vm, int retentionLimit) {
        List<VmBackup> normalBackups = vm.backups().stream()
                .filter(backup -> !backup.stable())
                .sorted(Comparator.comparing(VmBackup::backupTime))
                .toList();

        int excess = normalBackups.size() - retentionLimit;

        return normalBackups.stream()
                .limit(Math.max(0, excess))
                .toList();
    }

    private long normalBackupCount(VmOverview vm) {
        return vm.backups().stream()
                .filter(backup -> !backup.stable())
                .count();
    }
}
