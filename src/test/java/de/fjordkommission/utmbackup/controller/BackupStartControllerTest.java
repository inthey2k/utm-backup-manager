package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.service.BackupJobService;
import de.fjordkommission.utmbackup.service.RetentionService;
import de.fjordkommission.utmbackup.service.VmInventory;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupStartControllerTest {

    private final BackupJobService backupJobService =
            mock(BackupJobService.class);

    private final MessageSource messageSource =
            mock(MessageSource.class);

    private final VmInventory vmInventory =
            mock(VmInventory.class);

    private final RetentionService retentionService =
            mock(RetentionService.class);

    private final BackupStartController controller =
            new BackupStartController(
                    backupJobService,
                    messageSource,
                    vmInventory,
                    retentionService
            );

    private final VmId utmVmId = new VmId("utm", "test-vm");

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
        prepareInventory(managed(utmVmId, VmStatus.STOPPED));

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("utm:missing-vm"),
                null,
                redirectAttributes,
                false
        );

        assertEquals("redirect:/", result);
        assertEquals(
                "VM not found: utm:missing-vm",
                redirectAttributes.getFlashAttributes().get("error")
        );

        verifyNoInteractions(backupJobService);
        verifyNoInteractions(retentionService);
    }

    @Test
    void rejectsMalformedIdentifierAsNotFound() {
        prepareInventory(managed(utmVmId, VmStatus.STOPPED));

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        controller.backup(
                List.of("test-vm"),
                null,
                redirectAttributes,
                false
        );

        assertEquals(
                "VM not found: test-vm",
                redirectAttributes.getFlashAttributes().get("error")
        );

        verifyNoInteractions(backupJobService);
    }

    @Test
    void rejectsVmThatIsNotStopped() {
        prepareInventory(managed(utmVmId, VmStatus.RUNNING));

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("utm:test-vm"),
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
    void statusFromInventoryIsUsedAndUnknownBlocksBackup() {
        // An UNKNOWN status from the inventory must never be treated as stopped.
        prepareInventory(managed(utmVmId, VmStatus.UNKNOWN));

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        controller.backup(
                List.of("utm:test-vm"),
                null,
                redirectAttributes,
                false
        );

        assertEquals(
                "Backup cannot be created because VM Test VM is UNKNOWN.",
                redirectAttributes.getFlashAttributes().get("error")
        );

        verifyNoInteractions(backupJobService);
    }

    @Test
    void startsBackupWithoutRetentionCleanupWhenNotRequired() {
        prepareStoppedVm();

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(utmVmId)))
                .thenReturn(List.of());

        prepareStartedMessage();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("utm:test-vm"),
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

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(utmVmId)))
                .thenReturn(List.of(utmVmId));

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("utm:test-vm"),
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

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(utmVmId)))
                .thenReturn(List.of(utmVmId));

        prepareStartedMessage();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("utm:test-vm"),
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
                List.of(utmVmId)
        );
    }

    @Test
    void confirmedRetentionDoesNotScheduleCleanupWhenNoneIsRequired() {
        prepareStoppedVm();

        when(retentionService.findVmsRequiringRetentionCleanup(List.of(utmVmId)))
                .thenReturn(List.of());

        prepareStartedMessage();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String result = controller.backup(
                List.of("utm:test-vm"),
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

    @Test
    void requestIdsWithSpacesDotsAndColonsResolveToTheirVm() {
        LocalVm archLinux = new LocalVm(
                "ArchLinux 02.27.utm",
                "ArchLinux 02.27",
                List.of(Path.of("ArchLinux 02.27.utm"))
        );
        prepareInventory(new ManagedVm(
                new VmId("utm", "ArchLinux 02.27.utm"),
                "UTM",
                archLinux,
                VmStatus.STOPPED
        ));
        when(retentionService.findVmsRequiringRetentionCleanup(anyList())).thenReturn(List.of());
        prepareStartedMessage();

        controller.backup(
                List.of("utm:ArchLinux 02.27.utm"),
                null,
                new RedirectAttributesModelMap(),
                false
        );

        verify(backupJobService).start(List.of(archLinux), null, List.of());
    }

    @Test
    void sameLocalIdInTwoProvidersAddressesTwoDistinctVms() {
        LocalVm utmArch = new LocalVm("ArchLinux", "ArchLinux", List.of(Path.of("utm-ArchLinux.utm")));
        LocalVm qemuArch = new LocalVm("ArchLinux", "ArchLinux", List.of(Path.of("qemu-ArchLinux")));
        prepareInventory(
                new ManagedVm(new VmId("utm", "ArchLinux"), "UTM", utmArch, VmStatus.STOPPED),
                new ManagedVm(new VmId("qemu", "ArchLinux"), "QEMU", qemuArch, VmStatus.STOPPED)
        );
        when(retentionService.findVmsRequiringRetentionCleanup(anyList())).thenReturn(List.of());
        prepareStartedMessage();

        // The UTM VM is selected and backed up; the QEMU VM is rejected, not silently merged.
        controller.backup(
                List.of("utm:ArchLinux"),
                null,
                new RedirectAttributesModelMap(),
                false
        );
        verify(backupJobService).start(List.of(utmArch), null, List.of());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        controller.backup(
                List.of("qemu:ArchLinux"),
                null,
                redirectAttributes,
                false
        );
        assertEquals(
                "Backups of QEMU VMs are not supported yet. Select only VMs of the UTM provider.",
                redirectAttributes.getFlashAttributes().get("error")
        );
    }

    @Test
    void nonUtmVmIsRejectedBeforeAnyStateOrRetentionCheck() {
        prepareInventory(new ManagedVm(
                new VmId("qemu", "ArchLinux"),
                "QEMU",
                new LocalVm("ArchLinux", "ArchLinux", List.of(Path.of("ArchLinux"))),
                VmStatus.STOPPED
        ));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        controller.backup(
                List.of("qemu:ArchLinux"),
                null,
                redirectAttributes,
                false
        );

        assertEquals(
                "Backups of QEMU VMs are not supported yet. Select only VMs of the UTM provider.",
                redirectAttributes.getFlashAttributes().get("error")
        );
        verifyNoInteractions(backupJobService);
        verifyNoInteractions(retentionService);
    }

    @Test
    void mixedProviderBatchIsRejectedAsAWhole() {
        LocalVm utmVm = new LocalVm("ArchLinux", "ArchLinux", List.of(Path.of("utm")));
        LocalVm qemuVm = new LocalVm("FreeBSD", "FreeBSD", List.of(Path.of("qemu")));
        prepareInventory(
                new ManagedVm(new VmId("utm", "ArchLinux"), "UTM", utmVm, VmStatus.STOPPED),
                new ManagedVm(new VmId("qemu", "FreeBSD"), "QEMU", qemuVm, VmStatus.STOPPED)
        );

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        controller.backup(
                List.of("utm:ArchLinux", "qemu:FreeBSD"),
                null,
                redirectAttributes,
                false
        );

        assertEquals(
                "Backups of QEMU VMs are not supported yet. Select only VMs of the UTM provider.",
                redirectAttributes.getFlashAttributes().get("error")
        );
        verifyNoInteractions(backupJobService);
    }

    private ManagedVm managed(VmId id, VmStatus status) {
        return new ManagedVm(id, "UTM", vm, status);
    }

    private void prepareStoppedVm() {
        prepareInventory(managed(utmVmId, VmStatus.STOPPED));
    }

    private void prepareInventory(ManagedVm... vms) {
        when(vmInventory.build()).thenReturn(
                new VmInventory.Inventory(List.of(), List.of(vms))
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
