package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmOverview;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RetentionService {

    private final BackupScanner scanner;
    private final BackupService backupService;

    public RetentionService(
            BackupScanner scanner,
            BackupService backupService
    ) {
        this.scanner = scanner;
        this.backupService = backupService;
    }

    /**
     * Returns selected VMs for which another normal backup would
     * require a retention cleanup.
     */
    public List<LocalVm> findVmsRequiringRetentionCleanup(
            List<LocalVm> selectedVms
    ) {
        var overview = scanner.scan();
        int retentionLimit = overview.retentionLimit();

        Set<String> affectedVmIds = overview.vms().stream()
                .filter(vm -> normalBackupCount(vm) >= retentionLimit)
                .map(VmOverview::id)
                .collect(Collectors.toSet());

        return selectedVms.stream()
                .filter(vm -> affectedVmIds.contains(vm.id()))
                .toList();
    }

    /**
     * Deletes the oldest normal backups until the retention limit
     * is satisfied for the supplied VMs.
     */
    public void deleteBackupsExceedingRetention(List<LocalVm> vms) {
        var overview = scanner.scan();
        int retentionLimit = overview.retentionLimit();

        Set<String> vmIds = vms.stream()
                .map(LocalVm::id)
                .collect(Collectors.toSet());

        for (VmOverview vm : overview.vms()) {
            if (!vmIds.contains(vm.id())) {
                continue;
            }

            List<VmBackup> normalBackups = vm.backups().stream()
                    .filter(backup -> !backup.stable())
                    .sorted(Comparator.comparing(VmBackup::backupTime))
                    .toList();

            int excess = normalBackups.size() - retentionLimit;

            normalBackups.stream()
                    .limit(Math.max(0, excess))
                    .map(VmBackup::id)
                    .forEach(backupService::delete);
        }
    }

    private long normalBackupCount(VmOverview vm) {
        return vm.backups().stream()
                .filter(backup -> !backup.stable())
                .count();
    }
}