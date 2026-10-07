package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.service.BackupJobService;

import java.util.List;
import java.util.Map;

import de.fjordkommission.utmbackup.service.RetentionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BackupStartController {

    private static final String REDIRECT_HOME = "redirect:/";
    private static final String FLASH_MESSAGE = "message";
    private static final String FLASH_ERROR = "error";

    private final BackupJobService backupJobService;
    private final MessageSource messageSource;
    private final VmProviderRegistry vmProviderRegistry;
    private final RetentionService retentionService;

    public BackupStartController(
            BackupJobService backupJobService,
            MessageSource messageSource,
            VmProviderRegistry vmProviderRegistry,
            RetentionService retentionService
    ) {
        this.backupJobService = backupJobService;
        this.messageSource = messageSource;
        this.vmProviderRegistry = vmProviderRegistry;
        this.retentionService = retentionService;
    }

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

            var provider = vmProviderRegistry.current();

            List<LocalVm> localVms = provider.findAll();
            Map<String, VmStatus> statuses = provider.findStatuses();

            List<LocalVm> selectedVms = vmIds.stream()
                    .map(vmId -> localVms.stream()
                            .filter(vm -> vm.id().equals(vmId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "VM not found: " + vmId
                            )))
                    .toList();

            for (LocalVm vm : selectedVms) {
                VmStatus status = statuses.getOrDefault(
                        vm.name(),
                        VmStatus.UNKNOWN
                );

                if (status != VmStatus.STOPPED) {
                    throw new IllegalStateException(
                            "Backup cannot be created because VM "
                                    + vm.name()
                                    + " is "
                                    + status
                                    + "."
                    );
                }
            }

            List<LocalVm> retentionCleanupVms =
                    retentionService.findVmsRequiringRetentionCleanup(selectedVms);

            if (!retentionCleanupVms.isEmpty() && !retentionConfirmed) {
                throw new IllegalStateException(
                        "Retention cleanup confirmation is required."
                );
            }

            backupJobService.start(
                    selectedVms,
                    comment,
                    retentionConfirmed
                            ? retentionCleanupVms
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
}