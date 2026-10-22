package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.BackupStorageMetadataReader;
import de.fjordkommission.utmbackup.storage.BackupStorageMetadataResult;
import de.fjordkommission.utmbackup.storage.BackupStorageReadOnlyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static de.fjordkommission.utmbackup.TestVms.stoppedUtm;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Production format-2 writing: layout, manifest, root marker and failure behaviour.
 */
class FormatTwoWriteTest {

    private static final Pattern OFFSET_TIME = Pattern.compile("(?m)^Time: \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");

    @TempDir
    Path tempDir;

    private Path root;
    private final BackupManifestReader manifestReader = new BackupManifestReader();
    private final BackupStorageMetadataReader metadataReader = new BackupStorageMetadataReader();

    private CreateBackupService service(String version) throws IOException {
        root = tempDir.resolve("backups");
        Files.createDirectories(root);

        BuildProperties buildProperties = mock(BuildProperties.class);
        when(buildProperties.getVersion()).thenReturn(version);

        return new CreateBackupService(
                new BackupProperties(root, null, null),
                mock(VmChangeStatusService.class),
                new BackupStorageGuard(new BackupProperties(root, null, null)),
                buildProperties
        );
    }

    private Path archPackage(String name) throws IOException {
        Path source = tempDir.resolve("source").resolve(name);
        Files.createDirectories(source.resolve("Data"));
        Files.writeString(source.resolve("config.plist"), "config of " + name);
        Files.writeString(source.resolve("Data").resolve("disk.img"), "disk of " + name);
        return source;
    }

    private static ManagedVm vm(String localId, String name, Path source) {
        return stoppedUtm(new LocalVm(localId, name, List.of(source)));
    }

    private Path onlyBatch() throws IOException {
        try (Stream<Path> batches = Files.list(root)) {
            List<Path> found = batches.filter(p -> p.getFileName().toString().startsWith("20")).toList();
            assertEquals(1, found.size(), found.toString());
            return found.getFirst();
        }
    }

    private BackupManifest manifestOf(Path batch) {
        BackupManifestResult result = manifestReader.read(batch);
        assertInstanceOf(BackupManifestResult.Parsed.class, result, String.valueOf(result));
        return ((BackupManifestResult.Parsed) result).manifest();
    }

    @Test
    void oneUtmVmIsWrittenIntoAFormatTwoContainerWithItsPackageUnchanged() throws IOException {
        CreateBackupService service = service("1.5.0");
        Path source = archPackage("ArchLinux 02.27.utm");

        CreateBackupService.Result result = service.create(
                List.of(vm("ArchLinux 02.27.utm", "ArchLinux 02.27", source)), "Before kernel update");

        Path container = result.backupDirectory().resolve("VMs").resolve("utm__ArchLinux-02-27-utm-6a667c24");
        assertEquals("config of ArchLinux 02.27.utm",
                Files.readString(container.resolve("ArchLinux 02.27.utm").resolve("config.plist")));
        assertEquals("disk of ArchLinux 02.27.utm",
                Files.readString(container.resolve("ArchLinux 02.27.utm").resolve("Data").resolve("disk.img")));

        String manifest = Files.readString(result.backupDirectory().resolve("BACKUP-INFO.txt"));
        assertTrue(manifest.contains("Format-Version: 2"), manifest);
        assertTrue(manifest.contains("Provider: utm"), manifest);
        assertTrue(manifest.contains("VM-ID: ArchLinux 02.27.utm"), manifest);
        assertTrue(manifest.contains("Name: ArchLinux 02.27"), manifest);
        assertTrue(manifest.contains("Directory: utm__ArchLinux-02-27-utm-6a667c24"), manifest);
    }

    @Test
    void manifestEntriesMatchTheDirectoriesOnDisk() throws IOException {
        CreateBackupService service = service("1.5.0");
        Path first = archPackage("First.utm");
        Path second = archPackage("Second.utm");

        CreateBackupService.Result result = service.create(List.of(
                vm("First.utm", "First", first),
                vm("Second.utm", "Second", second)), "two VMs");

        BackupManifest manifest = manifestOf(result.backupDirectory());

        assertEquals(2, manifest.vms().size());
        for (BackupManifest.VmEntry entry : manifest.vms()) {
            Path container = result.backupDirectory().resolve("VMs").resolve(entry.directory());
            assertTrue(Files.isDirectory(container), entry.directory());
            assertTrue(Files.isDirectory(container.resolve(entry.id().localId())), entry.id().toString());
        }
    }

    @Test
    void multipleVmsGetDistinctContainersEachHoldingOnlyItsOwnPackage() throws IOException {
        CreateBackupService service = service("1.5.0");

        CreateBackupService.Result result = service.create(List.of(
                vm("First.utm", "First", archPackage("First.utm")),
                vm("Second.utm", "Second", archPackage("Second.utm"))), null);

        try (Stream<Path> containers = Files.list(result.backupDirectory().resolve("VMs"))) {
            List<Path> list = containers.sorted().toList();
            assertEquals(2, list.size());
            for (Path container : list) {
                try (Stream<Path> children = Files.list(container)) {
                    assertEquals(1, children.count(), container.toString());
                }
            }
        }
    }

    @Test
    void rootMetadataIsCreatedOnFirstSuccessfulFormatTwoWrite() throws IOException {
        CreateBackupService service = service("1.5.0");
        assertInstanceOf(BackupStorageMetadataResult.Absent.class, metadataReader.read(root));

        service.create(List.of(vm("Linux.utm", "Linux", archPackage("Linux.utm"))), null);

        BackupStorageMetadataResult result = metadataReader.read(root);
        assertInstanceOf(BackupStorageMetadataResult.Present.class, result, String.valueOf(result));
        var metadata = ((BackupStorageMetadataResult.Present) result).metadata();
        assertEquals(BackupFormat.PROVIDER_AWARE, metadata.formatVersion());
        assertEquals("UTM Backup Manager", metadata.applicationName());
        assertEquals("1.5.0", metadata.createdByVersion());
        assertEquals("1.5.0", metadata.lastWrittenByVersion());
    }

    @Test
    void rootMetadataIsNotCreatedWhenTheBackupFails() throws IOException {
        CreateBackupService service = service("1.5.0");
        List<ManagedVm> missing = List.of(vm("Missing.utm", "Missing", tempDir.resolve("does-not-exist")));

        assertThrows(UncheckedIOException.class, () -> service.create(missing, null));

        assertInstanceOf(BackupStorageMetadataResult.Absent.class, metadataReader.read(root));
        assertEquals(List.of(), entries());
    }

    @Test
    void noFormatOneBatchIsWrittenAndEveryVmDirectoryIsAContainer() throws IOException {
        CreateBackupService service = service("1.5.0");

        service.create(List.of(vm("Linux.utm", "Linux", archPackage("Linux.utm"))), null);

        Path batch = onlyBatch();
        assertEquals(BackupFormat.PROVIDER_AWARE, manifestOf(batch).formatVersion());
        try (Stream<Path> children = Files.list(batch.resolve("VMs"))) {
            assertTrue(children.allMatch(child -> child.getFileName().toString().startsWith("utm__")));
        }
    }

    @Test
    void commentRoundTripsAsTheUserWroteIt() throws IOException {
        CreateBackupService service = service("1.5.0");

        service.create(List.of(vm("Linux.utm", "Linux", archPackage("Linux.utm"))),
                "  Before kernel update\n\nsecond paragraph  ");

        assertEquals("Before kernel update\n\nsecond paragraph", manifestOf(onlyBatch()).comment());
    }

    @Test
    void manifestTimeIsWrittenWithAnOffset() throws IOException {
        CreateBackupService service = service("1.5.0");

        service.create(List.of(vm("Linux.utm", "Linux", archPackage("Linux.utm"))), null);

        String manifest = Files.readString(onlyBatch().resolve("BACKUP-INFO.txt"));
        assertTrue(OFFSET_TIME.matcher(manifest).find(), manifest);
        assertTrue(manifestOf(onlyBatch()).backupTime().isPresent());
    }

    @Test
    void existingMarkerKeepsItsCreationFieldsAndIsOnlyRewrittenForANewVersion() throws IOException {
        service("1.5.0").create(List.of(vm("Linux.utm", "Linux", archPackage("Linux.utm"))), null);
        var created = ((BackupStorageMetadataResult.Present) metadataReader.read(root)).metadata();
        String sameVersionBytes = Files.readString(root.resolve(BackupStorageMetadataReader.FILE_NAME));

        service("1.5.0").create(List.of(vm("Linux2.utm", "Linux2", archPackage("Linux2.utm"))), null);
        assertEquals(sameVersionBytes, Files.readString(root.resolve(BackupStorageMetadataReader.FILE_NAME)));

        service("1.6.0").create(List.of(vm("Linux3.utm", "Linux3", archPackage("Linux3.utm"))), null);
        var updated = ((BackupStorageMetadataResult.Present) metadataReader.read(root)).metadata();

        assertEquals(created.createdAt(), updated.createdAt());
        assertEquals("1.5.0", updated.createdByVersion());
        assertEquals("1.6.0", updated.lastWrittenByVersion());
    }

    @Test
    void newerMarkerRefusesTheBackupAndIsNotOverwritten() throws IOException {
        CreateBackupService service = service("1.5.0");
        String newer = "format.version=3\ncreated.at=2026-10-21T12:00:00+02:00\n";
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), newer, StandardCharsets.UTF_8);
        List<ManagedVm> vms = List.of(vm("Linux.utm", "Linux", archPackage("Linux.utm")));

        assertThrows(BackupStorageReadOnlyException.class, () -> service.create(vms, null));

        assertEquals(newer, Files.readString(root.resolve(BackupStorageMetadataReader.FILE_NAME)));
        assertEquals(List.of(BackupStorageMetadataReader.FILE_NAME), entries());
    }

    @Test
    void failedBackupLeavesNoPublishedBatchAndNoTemporaryArtifacts() throws IOException {
        CreateBackupService service = service("1.5.0");
        Path source = archPackage("Linux.utm");
        ManagedVm good = vm("Linux.utm", "Linux", source);
        List<ManagedVm> batch = List.of(good, vm("Missing.utm", "Missing", tempDir.resolve("gone")));

        assertThrows(UncheckedIOException.class, () -> service.create(batch, null));

        assertEquals(List.of(), entries());
    }

    private List<String> entries() throws IOException {
        try (Stream<Path> paths = Files.list(root)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
