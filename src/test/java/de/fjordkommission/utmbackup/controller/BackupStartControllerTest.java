package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.service.BackupJobService;
import de.fjordkommission.utmbackup.service.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupStartControllerTest {

    private final BackupJobService backupJobService =
            mock(BackupJobService.class);

    private final MessageSource messageSource =
            mock(MessageSource.class);

    private final VmProviderRegistry vmProviderRegistry =
            mock(VmProviderRegistry.class);

    private final RetentionService retentionService =
            mock(RetentionService.class);

    private final VmProvider provider =
            mock(VmProvider.class);

    private final BackupStartController controller =
            new BackupStartController(
                    backupJobService,
                    messageSource,
                    vmProviderRegistry,
                    retentionService
            );

    private final LocalVm vm = new LocalVm(
            "test-vm",
            "Test VM",
            List.of(Path.of("Test.utm"))
    );

    @Test
    void rejectsMissingVmSelection() {
        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                null,
                null,
                redirectAttributes,
                false
        );

        assertEquals("redirect:/", result);
        assertEquals(
                "At least one VM must be selected.",
                redirectAttributes.getFlashAttributes().get("error")
        );

        verifyNoInteractions(backupJobService);
        verifyNoInteractions(retentionService);
    }

    @Test
    void rejectsUnknownVm() {
        when(vmProviderRegistry.current()).thenReturn(provider);
        when(provider.findAll()).thenReturn(List.of(vm));
        when(provider.findStatuses()).thenReturn(Map.of());

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("missing-vm"),
                null,
                redirectAttributes,
                false
        );

        assertEquals("redirect:/", result);
        assertEquals(
                "VM not found: missing-vm",
                redirectAttributes.getFlashAttributes().get("error")
        );

        verifyNoInteractions(backupJobService);
        verifyNoInteractions(retentionService);
    }

    @Test
    void rejectsVmThatIsNotStopped() {
        when(vmProviderRegistry.current()).thenReturn(provider);
        when(provider.findAll()).thenReturn(List.of(vm));
        when(provider.findStatuses()).thenReturn(
                Map.of("Test VM", VmStatus.RUNNING)
        );

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("test-vm"),
                null,
                redirectAttributes,
                false
        );

        assertEquals("redirect:/", result);
        assertEquals(
                "Backup cannot be created because VM Test VM is RUNNING.",
                redirectAttributes.getFlashAttributes().get("error")
        );

        verifyNoInteractions(backupJobService);
        verifyNoInteractions(retentionService);
    }

    @Test
    void startsBackupWithoutRetentionCleanupWhenNotRequired() {
        prepareStoppedVm();

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(vm)))
                .thenReturn(List.of());

        prepareStartedMessage();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("test-vm"),
                "Test comment",
                redirectAttributes,
                false
        );

        assertEquals("redirect:/", result);
        assertEquals(
                "Backup started.",
                redirectAttributes.getFlashAttributes().get("message")
        );

        verify(backupJobService).start(
                List.of(vm),
                "Test comment",
                List.of()
        );
    }

    @Test
    void rejectsBackupWhenRetentionCleanupRequiresConfirmation() {
        prepareStoppedVm();

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(vm)))
                .thenReturn(List.of(vm));

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("test-vm"),
                "Test comment",
                redirectAttributes,
                false
        );

        assertEquals("redirect:/", result);
        assertEquals(
                "Retention cleanup confirmation is required.",
                redirectAttributes.getFlashAttributes().get("error")
        );

        verifyNoInteractions(backupJobService);
    }

    @Test
    void startsBackupWithRetentionCleanupWhenConfirmed() {
        prepareStoppedVm();

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(vm)))
                .thenReturn(List.of(vm));

        prepareStartedMessage();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("test-vm"),
                "Test comment",
                redirectAttributes,
                true
        );

        assertEquals("redirect:/", result);
        assertEquals(
                "Backup started.",
                redirectAttributes.getFlashAttributes().get("message")
        );

        verify(backupJobService).start(
                List.of(vm),
                "Test comment",
                List.of(vm)
        );
    }

    @Test
    void confirmedRetentionDoesNotScheduleCleanupWhenNoneIsRequired() {
        prepareStoppedVm();

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(vm)))
                .thenReturn(List.of());

        prepareStartedMessage();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("test-vm"),
                "Test comment",
                redirectAttributes,
                true
        );

        assertEquals("redirect:/", result);

        verify(backupJobService).start(
                List.of(vm),
                "Test comment",
                List.of()
        );
    }

    private void prepareStoppedVm() {
        when(vmProviderRegistry.current()).thenReturn(provider);
        when(provider.findAll()).thenReturn(List.of(vm));
        when(provider.findStatuses()).thenReturn(
                Map.of("Test VM", VmStatus.STOPPED)
        );
    }

    private void prepareStartedMessage() {
        when(messageSource.getMessage(
                eq("backup.started"),
                any(Object[].class),
                any()
        )).thenReturn("Backup started.");
    }
}