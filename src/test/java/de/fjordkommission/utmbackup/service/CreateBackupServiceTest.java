package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateBackupServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsBackupWithFilesAndMetadata() throws Exception {
        Path source = tempDirectory.resolve("TestLinux.utm");
        Files.createDirectories(source.resolve("Data"));
        Files.writeString(source.resolve("config.plist"), "configuration");
        Files.writeString(source.resolve("Data/disk.img"), "virtual disk");

        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);

        CreateBackupService service = createService(backupRoot);

        LocalVm vm = new LocalVm(
                "test-linux-vm",
                "Test Linux VM",
                List.of(source)
        );

        CreateBackupService.Result result = service.create(vm);

        Path backupDirectory = result.backupDirectory();
        Path copiedVm = backupDirectory.resolve("VMs/TestLinux.utm");

        assertTrue(Files.isDirectory(backupDirectory));
        assertTrue(Files.isDirectory(copiedVm));
        assertEquals(
                "configuration",
                Files.readString(copiedVm.resolve("config.plist"))
        );
        assertEquals(
                "virtual disk",
                Files.readString(copiedVm.resolve("Data/disk.img"))
        );

        Path backupInfo = backupDirectory.resolve("BACKUP-INFO.txt");

        assertTrue(Files.isRegularFile(backupInfo));

        String info = Files.readString(backupInfo);

        assertTrue(info.contains("VMs:"));
        assertTrue(info.contains("test-linux-vm"));
        assertTrue(info.contains("Comment:"));
        assertFalse(
                Files.exists(
                        backupRoot.resolve(
                                ".incomplete-" + result.backupDirectoryName()
                        )
                )
        );

        // Backup creation must never modify or remove the source.
        assertEquals(
                "configuration",
                Files.readString(source.resolve("config.plist"))
        );
        assertEquals(
                "virtual disk",
                Files.readString(source.resolve("Data/disk.img"))
        );
    }

    @Test
    void createsBatchBackupForMultipleVms() throws Exception {
        Path firstSource = tempDirectory.resolve("First.utm");
        Path secondSource = tempDirectory.resolve("Second.utm");

        Files.createDirectories(firstSource);
        Files.createDirectories(secondSource);

        Files.writeString(
                firstSource.resolve("config.plist"),
                "first"
        );
        Files.writeString(
                secondSource.resolve("config.plist"),
                "second"
        );

        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);

        BackupProperties properties = new BackupProperties(
                backupRoot,
                tempDirectory.resolve("metadata.db"),
                tempDirectory.resolve("backup-state.tsv")
        );

        VmChangeStatusService vmChangeStatusService =
                mock(VmChangeStatusService.class);

        CreateBackupService service = new CreateBackupService(
                properties,
                vmChangeStatusService
        );

        LocalVm firstVm = new LocalVm(
                "first-vm",
                "First VM",
                List.of(firstSource)
        );

        LocalVm secondVm = new LocalVm(
                "second-vm",
                "Second VM",
                List.of(secondSource)
        );

        CreateBackupService.Result result = service.create(
                List.of(firstVm, secondVm),
                "Before system updates"
        );

        Path backupDirectory = result.backupDirectory();

        assertEquals(
                "first",
                Files.readString(
                        backupDirectory.resolve("VMs/First.utm/config.plist")
                )
        );

        assertEquals(
                "second",
                Files.readString(
                        backupDirectory.resolve("VMs/Second.utm/config.plist")
                )
        );

        String info = Files.readString(
                backupDirectory.resolve("BACKUP-INFO.txt")
        );

        assertTrue(info.contains("first-vm"));
        assertTrue(info.contains("second-vm"));
        assertTrue(info.contains("Before system updates"));

        verify(vmChangeStatusService).acknowledgeBackup(
                "first-vm",
                firstVm
        );

        verify(vmChangeStatusService).acknowledgeBackup(
                "second-vm",
                secondVm
        );
    }

    @Test
    void cancelsBackupAndRemovesIncompleteDirectory() throws Exception {
        Path source = tempDirectory.resolve("TestLinux.utm");
        Files.createDirectories(source);

        Path disk = source.resolve("disk.img");
        byte[] content = new byte[20 * 1024 * 1024];
        Files.write(disk, content);

        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);

        CreateBackupService service = createService(backupRoot);

        LocalVm vm = new LocalVm(
                "test-linux-vm",
                "Test Linux VM",
                List.of(source)
        );

        AtomicBoolean cancel = new AtomicBoolean(false);
        List<LocalVm> vms = List.of(vm);

        assertThrows(
                BackupCancelledException.class,
                () -> service.create(
                        vms,
                        "Cancellation test",
                        progress -> {
                            if (progress.copiedBytes() > 0) {
                                cancel.set(true);
                            }
                        },
                        cancel::get
                )
        );

        try (var entries = Files.list(backupRoot)) {
            assertTrue(
                    entries.findAny().isEmpty(),
                    "Cancelled backup must not leave data behind"
            );
        }
    }

    @Test
    void reportsProgressWhileCopying() throws Exception {
        Path source = tempDirectory.resolve("TestLinux.utm");
        Files.createDirectories(source);

        Path disk = source.resolve("disk.img");
        byte[] content = new byte[20 * 1024 * 1024];
        Files.write(disk, content);

        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);
        CreateBackupService service = createService(backupRoot);

        LocalVm vm = new LocalVm(
                "test-linux-vm",
                "Test Linux VM",
                List.of(source)
        );

        List<CreateBackupService.Progress> updates = new ArrayList<>();

        service.create(
                List.of(vm),
                "Progress test",
                updates::add
        );

        assertFalse(updates.isEmpty());

        CreateBackupService.Progress first = updates.getFirst();
        CreateBackupService.Progress last = updates.getLast();

        assertEquals("Test Linux VM", first.currentVmName());
        assertEquals(1, first.currentVmNumber());
        assertEquals(1, first.vmCount());
        assertEquals(content.length, first.totalBytes());

        assertEquals(content.length, last.copiedBytes());
        assertEquals(content.length, last.totalBytes());

        assertTrue(
                updates.stream().anyMatch(progress ->
                        progress.copiedBytes() > 0
                                && progress.copiedBytes() < progress.totalBytes()
                ),
                "Large files should produce intermediate byte progress"
        );
    }

    @Test
    void doesNotAcknowledgeAnyVmWhenBatchBackupFails() throws Exception {
        Path existingSource = tempDirectory.resolve("Existing.utm");
        Files.createDirectories(existingSource);
        Files.writeString(
                existingSource.resolve("config.plist"),
                "existing"
        );

        Path missingSource = tempDirectory.resolve("Missing.utm");
        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);
        BackupProperties properties = new BackupProperties(
                backupRoot,
                tempDirectory.resolve("metadata.db"),
                tempDirectory.resolve("backup-state.tsv")
        );

        VmChangeStatusService vmChangeStatusService =
                mock(VmChangeStatusService.class);

        CreateBackupService service = new CreateBackupService(
                properties,
                vmChangeStatusService
        );

        LocalVm existingVm = new LocalVm(
                "existing-vm",
                "Existing VM",
                List.of(existingSource)
        );

        LocalVm missingVm = new LocalVm(
                "missing-vm",
                "Missing VM",
                List.of(missingSource)
        );

        List<LocalVm> vms = List.of(existingVm, missingVm);

        assertThrows(
                UncheckedIOException.class,
                () -> service.create(
                        vms,
                        "This backup must fail"
                )
        );

        verifyNoInteractions(vmChangeStatusService);

        if (Files.exists(backupRoot)) {
            try (var entries = Files.list(backupRoot)) {
                assertTrue(
                        entries.findAny().isEmpty(),
                        "Failed batch backup must not leave data behind"
                );
            }
        }
    }

    @Test
    void removesIncompleteBackupWhenSourceDoesNotExist() throws Exception {
        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);
        Path missingSource = tempDirectory.resolve("Missing.utm");

        CreateBackupService service = createService(backupRoot);

        LocalVm vm = new LocalVm(
                "missing-vm",
                "Missing VM",
                List.of(missingSource)
        );

        assertThrows(
                UncheckedIOException.class,
                () -> service.create(vm)
        );

        if (Files.exists(backupRoot)) {
            try (var entries = Files.list(backupRoot)) {
                assertTrue(
                        entries.findAny().isEmpty(),
                        "Failed backup must not leave files or directories behind"
                );
            }
        }
    }

    @Test
    void rejectsVmWithoutBackupSources() {
        Path backupRoot = tempDirectory.resolve("backups");

        CreateBackupService service = createService(backupRoot);

        LocalVm vm = new LocalVm(
                "empty-vm",
                "Empty VM",
                List.of()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(vm)
        );

        assertTrue(exception.getMessage().contains("empty-vm"));
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void addsSuffixWhenBackupDirectoryAlreadyExists() throws Exception {
        Path source = tempDirectory.resolve("TestLinux.utm");
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.plist"), "configuration");

        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);
        CreateBackupService service = createService(backupRoot);

        LocalVm vm = new LocalVm(
                "test-linux-vm",
                "Test Linux VM",
                List.of(source)
        );

        CreateBackupService.Result first = service.create(vm);
        CreateBackupService.Result second = service.create(vm);
        CreateBackupService.Result third = service.create(vm);

        assertEquals(
                first.backupDirectoryName() + "_2",
                second.backupDirectoryName()
        );

        assertEquals(
                first.backupDirectoryName() + "_3",
                third.backupDirectoryName()
        );

        assertTrue(Files.isDirectory(first.backupDirectory()));
        assertTrue(Files.isDirectory(second.backupDirectory()));
        assertTrue(Files.isDirectory(third.backupDirectory()));
    }

    @Test
    void acknowledgesVmStateOnlyAfterSuccessfulBackup() throws Exception {
        Path source = tempDirectory.resolve("TestLinux.utm");
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.plist"), "configuration");

        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);
        BackupProperties properties = new BackupProperties(
                backupRoot,
                tempDirectory.resolve("metadata.db"),
                tempDirectory.resolve("backup-state.tsv")
        );

        VmChangeStatusService vmChangeStatusService =
                mock(VmChangeStatusService.class);

        CreateBackupService service = new CreateBackupService(
                properties,
                vmChangeStatusService
        );

        LocalVm vm = new LocalVm(
                "test-linux-vm",
                "Test Linux VM",
                List.of(source)
        );

        service.create(vm);

        verify(vmChangeStatusService).acknowledgeBackup(
                "test-linux-vm",
                vm
        );
    }

    @Test
    void doesNotAcknowledgeVmStateWhenBackupFails() throws Exception {
        Path backupRoot = tempDirectory.resolve("backups");
        Files.createDirectories(backupRoot);
        Path missingSource = tempDirectory.resolve("Missing.utm");

        BackupProperties properties = new BackupProperties(
                backupRoot,
                tempDirectory.resolve("metadata.db"),
                tempDirectory.resolve("backup-state.tsv")
        );

        VmChangeStatusService vmChangeStatusService =
                mock(VmChangeStatusService.class);

        CreateBackupService service = new CreateBackupService(
                properties,
                vmChangeStatusService
        );

        LocalVm vm = new LocalVm(
                "missing-vm",
                "Missing VM",
                List.of(missingSource)
        );

        assertThrows(
                UncheckedIOException.class,
                () -> service.create(vm)
        );

        verifyNoInteractions(vmChangeStatusService);
    }

    private CreateBackupService createService(Path backupRoot) {
        BackupProperties properties = new BackupProperties(
                backupRoot,
                tempDirectory.resolve("metadata.db"),
                tempDirectory.resolve("backup-state.tsv")
        );

        VmChangeStatusService vmChangeStatusService =
                mock(VmChangeStatusService.class);

        return new CreateBackupService(
                properties,
                vmChangeStatusService
        );
    }
}
