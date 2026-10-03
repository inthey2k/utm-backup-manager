package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.config.ApplicationConfigurationView;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.service.BackupJob;
import de.fjordkommission.utmbackup.service.BackupJobService;
import de.fjordkommission.utmbackup.service.BackupJobStatus;
import de.fjordkommission.utmbackup.service.BackupRootUnavailableException;
import de.fjordkommission.utmbackup.service.BackupScanner;
import de.fjordkommission.utmbackup.service.BackupService;
import de.fjordkommission.utmbackup.service.VmSizeService;

import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


@Controller
public class BackupController {

    private final ApplicationConfigurationView configurationView;
    private final BackupJobService backupJobService;
    private final BackupScanner scanner;
    private final BackupService service;
    private final MessageSource messageSource;
    private final SettingsRepository settings;
    private final VmProviderRegistry vmProviderRegistry;
    private final VmSizeService vmSizeService;
    private final BuildProperties buildProperties;

    public BackupController(
            BackupScanner scanner,
            BackupService service,
            SettingsRepository settings,
            VmProviderRegistry vmProviderRegistry,
            BackupJobService backupJobService,
            VmSizeService vmSizeService,
            ApplicationConfigurationView configurationView,
            MessageSource messageSource,
            BuildProperties buildProperties
    ) {
        this.scanner = scanner;
        this.service = service;
        this.settings = settings;
        this.vmProviderRegistry = vmProviderRegistry;
        this.backupJobService = backupJobService;
        this.vmSizeService = vmSizeService;
        this.configurationView = configurationView;
        this.messageSource = messageSource;
        this.buildProperties = buildProperties;
    }
    @GetMapping("/")
    String index(Model model) {
        var configuration = configurationView.current();

        model.addAttribute("configuration", configuration);
        model.addAttribute("backupRoot", configuration.backupRoot().toString());
        model.addAttribute("providerName", configuration.providerName());
        model.addAttribute(
                "backupJobRunning",
                backupJobService.currentJob()
                        .map(job -> job.status() == BackupJobStatus.RUNNING)
                        .orElse(false)
        );

        try {
            model.addAttribute("overview", scanner.scan());
            model.addAttribute("backupRootAvailable", true);
        } catch (BackupRootUnavailableException e) {
            model.addAttribute("backupRootAvailable", false);
        }
        model.addAttribute("applicationVersion", buildProperties.getVersion());

        return "index";
    }

    @PostMapping("/settings/retention")
    public String retentionLimit(@RequestParam int retentionLimit) {
        if (retentionLimit < 1 || retentionLimit > 99) {
            throw new IllegalArgumentException(
                    message("retention.invalid")
            );
        }
        settings.setRetentionLimit(retentionLimit);
        return "redirect:/";
    }

    @PostMapping("/backup")
    String backup(
            @RequestParam(required = false) List<String> vmIds,
            @RequestParam(required = false) String comment,
            RedirectAttributes redirectAttributes
    ) {
        try {
            if (vmIds == null || vmIds.isEmpty()) {
                throw new IllegalArgumentException(
                        message("backup.selection.required")
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
                                    message("vm.notFound", vmId)
                            )))
                            .toList();

            for (LocalVm vm : selectedVms) {
                VmStatus status = statuses.getOrDefault(
                        vm.name(),
                        VmStatus.UNKNOWN
                );

                if (status != VmStatus.STOPPED) {
                    throw new IllegalStateException(
                            message(
                                    "backup.vmNotStopped",
                                    vm.name(),
                                    status
                            )
                    );
                }
            }

            backupJobService.start(selectedVms, comment);

            redirectAttributes.addFlashAttribute(
                    "message",
                    message("backup.started", selectedVms.size())
            );

        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        }

        return "redirect:/";
    }

    @PostMapping("/stable/{id}")
    String stable(
            @PathVariable String id,
            @RequestParam boolean stable,
            RedirectAttributes redirectAttributes
    ) {
        try {
            service.stable(id, stable);

            redirectAttributes.addFlashAttribute(
                    "message",
                    message(
                            stable
                                    ? "stable.marked"
                                    : "stable.unmarked"
                    )
            );
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        }

        return "redirect:/";
    }

    @PostMapping("/note/{id}")
    String note(
            @PathVariable String id,
            @RequestParam(required = false) String note,
            RedirectAttributes redirectAttributes
    ) {
        try {
            service.note(id, note);

            redirectAttributes.addFlashAttribute(
                    "message",
                    message(
                            note == null || note.isBlank()
                                    ? "note.removed"
                                    : "note.saved"
                    )
            );
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        }

        return "redirect:/";
    }

    @PostMapping("/delete/{id}")
    String delete(
            @PathVariable String id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            service.delete(id);

            redirectAttributes.addFlashAttribute(
                    "message",
                    message("backup.deleted")
            );
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        }

        return "redirect:/";
    }

    /**
     * Returns the latest backup-job state for the browser poller.
     */
    @GetMapping("/backup/status")
    @ResponseBody
    BackupJobResponse backupStatus() {
        return backupJobService.currentJob()
                .map(job -> BackupJobResponse.from(
                        job,
                        backupJobService.currentVmNames()
                ))
                .orElseGet(BackupJobResponse::idle);
    }

    /**
     * Requests cancellation of the currently running backup.
     */
    @PostMapping("/backup/cancel")
    @ResponseBody
    void cancelBackup() {
        backupJobService.cancel();
    }

    @GetMapping("/vm/{vmId}/size")
    @ResponseBody
    VmSizeResponse vmSize(@PathVariable String vmId) {
        LocalVm vm = vmProviderRegistry.current()
                .findAll()
                .stream()
                .filter(candidate -> candidate.id().equals(vmId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "VM not found: " + vmId
                ));

        return new VmSizeResponse(
                vm.id(),
                vmSizeService.sizeOf(vm)
        );
    }

    private String message(String key, Object... arguments) {
        return messageSource.getMessage(
                key,
                arguments,
                LocaleContextHolder.getLocale()
        );
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
                                            : LocalDateTime.now()
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

    record VmSizeResponse(
            String vmId,
            long bytes
    ) {

    }
}