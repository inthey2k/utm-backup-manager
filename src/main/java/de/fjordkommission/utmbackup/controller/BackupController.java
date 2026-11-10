package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.config.ApplicationConfigurationView;
import de.fjordkommission.utmbackup.model.RetentionCleanupJobStatus;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.service.*;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;

import java.util.List;

import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BackupController {

    private final ApplicationConfigurationView configurationView;
    private final BackupJobService backupJobService;
    private final BackupScanner scanner;
    private final BackupService service;
    private final MessageSource messageSource;
    private final SettingsRepository settings;
    private final BuildProperties buildProperties;
    private final BackupStorageGuard storageGuard;
    private final RetentionService retentionService;
    private final RetentionCleanupJobService retentionCleanupJobService;

    private static final String REDIRECT_HOME = "redirect:/";
    private static final String FLASH_MESSAGE = "message";
    private static final String FLASH_ERROR = "error";
    private static final String FLASH_INFO = "info";

    public BackupController(
            BackupScanner scanner,
            BackupService service,
            SettingsRepository settings,
            BackupJobService backupJobService,
            ApplicationConfigurationView configurationView,
            MessageSource messageSource,
            BuildProperties buildProperties,
            BackupStorageGuard storageGuard,
            RetentionService retentionService,
            RetentionCleanupJobService retentionCleanupJobService
    ) {
        this.scanner = scanner;
        this.service = service;
        this.settings = settings;
        this.backupJobService = backupJobService;
        this.configurationView = configurationView;
        this.messageSource = messageSource;
        this.buildProperties = buildProperties;
        this.storageGuard = storageGuard;
        this.retentionService = retentionService;
        this.retentionCleanupJobService = retentionCleanupJobService;
    }
    @GetMapping("/")
    String index(Model model) {
        var configuration = configurationView.current();

        model.addAttribute("configuration", configuration);
        model.addAttribute("backupRoot", configuration.backupRoot().toString());
        model.addAttribute("providerName", configuration.providerName());
        // The guard stays authoritative for every change; the page only reflects its state.
        model.addAttribute("storageStatus", storageGuard.status());
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
        return REDIRECT_HOME;
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
                    FLASH_MESSAGE,
                    message(
                            stable
                                    ? "stable.marked"
                                    : "stable.unmarked"
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

    @PostMapping("/note/{id}")
    String note(
            @PathVariable String id,
            @RequestParam(required = false) String note,
            RedirectAttributes redirectAttributes
    ) {
        try {
            service.note(id, note);

            redirectAttributes.addFlashAttribute(
                    FLASH_MESSAGE,
                    message(
                            note == null || note.isBlank()
                                    ? "note.removed"
                                    : "note.saved"
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

    @PostMapping("/delete/{id}")
    String delete(
            @PathVariable String id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            service.delete(id);

            redirectAttributes.addFlashAttribute(
                    FLASH_MESSAGE,
                    message("backup.deleted")
            );
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(
                    FLASH_ERROR,
                    e.getMessage()
            );
        }

        return REDIRECT_HOME;
    }

    /**
     * Deletes every backup that is currently a retention deletion candidate. The candidates
     * are computed by the backend at execution time; nothing from the browser is trusted.
     */
    @PostMapping("/retention/delete-candidates")
    String deleteRetentionCandidates(RedirectAttributes redirectAttributes) {
        try {
            if (retentionCleanupRunning()) {
                redirectAttributes.addFlashAttribute(FLASH_ERROR, message("cleanup.running"));
                return REDIRECT_HOME;
            }

            int deleted = retentionService.deleteCurrentDeletionCandidates();

            if (deleted == 0) {
                redirectAttributes.addFlashAttribute(FLASH_INFO, message("cleanup.none"));
            } else {
                redirectAttributes.addFlashAttribute(FLASH_MESSAGE, message("cleanup.deleted", deleted));
            }
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(FLASH_ERROR, e.getMessage());
        }

        return REDIRECT_HOME;
    }

    /**
     * Permanently deletes the manually selected backups. The whole selection is validated
     * by the service before the first deletion.
     */
    @PostMapping("/backups/delete-selected")
    String deleteSelected(
            @RequestParam(required = false) List<String> backupIds,
            RedirectAttributes redirectAttributes
    ) {
        if (backupIds == null || backupIds.isEmpty()) {
            redirectAttributes.addFlashAttribute(FLASH_ERROR, message("selection.none"));
            return REDIRECT_HOME;
        }

        try {
            if (retentionCleanupRunning()) {
                redirectAttributes.addFlashAttribute(FLASH_ERROR, message("cleanup.running"));
                return REDIRECT_HOME;
            }

            int deleted = service.deleteAll(backupIds);

            redirectAttributes.addFlashAttribute(FLASH_MESSAGE, message("selection.deleted", deleted));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute(FLASH_ERROR, e.getMessage());
        }

        return REDIRECT_HOME;
    }

    /** Manual deletion must not race with the automatic retention cleanup after a backup. */
    private boolean retentionCleanupRunning() {
        return retentionCleanupJobService.currentJob()
                .map(job -> job.status() == RetentionCleanupJobStatus.RUNNING)
                .orElse(false);
    }

    private String message(String key, Object... arguments) {
        return messageSource.getMessage(
                key,
                arguments,
                LocaleContextHolder.getLocale()
        );
    }

}