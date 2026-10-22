package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.service.BackupJobService;
import de.fjordkommission.utmbackup.service.LegacyBackupLayout;
import de.fjordkommission.utmbackup.service.VmInventory;
import de.fjordkommission.utmbackup.service.RetentionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class BackupStartController {

    private static final String REDIRECT_HOME = "redirect:/";
    private static final String FLASH_MESSAGE = "message";
    private static final String FLASH_ERROR = "error";

    private final BackupJobService backupJobService;
    private final MessageSource messageSource;
    private final VmInventory vmInventory;
    private final RetentionService retentionService;

    public BackupStartController(
            BackupJobService backupJobService,
            MessageSource messageSource,
            VmInventory vmInventory,
            RetentionService retentionService
    ) {
        this.backupJobService = backupJobService;
        this.messageSource = messageSource;
        this.vmInventory = vmInventory;
        this.retentionService = retentionService;
    }

    /**
     * Starts a backup for the selected canonical VM ids ({@code providerId:localId}).
     * Backup writing still uses the legacy UTM layout, so a batch may contain
     * only UTM VMs. A non-UTM selection is rejected before anything is written.
     */
    @PostMapping("/backup")
    String backup(
            @RequestParam(required = false) List<String> vmIds,
            @RequestParam(required = false) String comment,
            RedirectAttributes redirectAttributes,
            @RequestParam(defaultValue = "false") boolean retentionConfirmed
    ) {
        try {
            if (vmIds == null || vmIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one VM must be selected."
                );
            }

            VmInventory.Inventory inventory = vmInventory.build();

            List<ManagedVm> selectedVms = vmIds.stream()
                    .map(vmId -> selectedVm(inventory, vmId))
                    .toList();

            for (ManagedVm vm : selectedVms) {
                // Backup writing is still limited to UTM. Its layout and change tracking are verified only for UTM.
                if (!LegacyBackupLayout.PROVIDER_ID.equals(vm.providerId())) {
                    throw new IllegalStateException(
                            "Backups of " + vm.providerName() + " VMs are not supported yet. "
                                    + "Select only VMs of the UTM provider."
                    );
                }
            }

            for (ManagedVm vm : selectedVms) {
                if (vm.status() != VmStatus.STOPPED) {
                    throw new IllegalStateException(
                            "Backup cannot be created because VM "
                                    + vm.name()
                                    + " is "
                                    + vm.status()
                                    + "."
                    );
                }
            }

            List<VmId> selectedIds = selectedVms.stream()
                    .map(ManagedVm::id)
                    .toList();

            List<VmId> retentionCleanupIds =
                    retentionService.findVmsRequiringRetentionCleanup(selectedIds);

            if (!retentionCleanupIds.isEmpty() && !retentionConfirmed) {
                throw new IllegalStateException(
                        "Retention cleanup confirmation is required."
                );
            }

            backupJobService.start(
                    selectedVms.stream().map(ManagedVm::localVm).toList(),
                    comment,
                    retentionConfirmed
                            ? retentionCleanupIds
                            : List.of()
            );

            redirectAttributes.addFlashAttribute(
                    FLASH_MESSAGE,
                    messageSource.getMessage(
                            "backup.started",
                            new Object[]{selectedVms.size()},
                            LocaleContextHolder.getLocale()
                    )
            );
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(
                    FLASH_ERROR,
                    e.getMessage()
            );
        }

        return REDIRECT_HOME;
    }

    private static ManagedVm selectedVm(VmInventory.Inventory inventory, String rawId) {
        return VmId.tryParse(rawId)
                .flatMap(inventory::find)
                .orElseThrow(() -> new IllegalArgumentException(
                        "VM not found: " + rawId
                ));
    }
}
