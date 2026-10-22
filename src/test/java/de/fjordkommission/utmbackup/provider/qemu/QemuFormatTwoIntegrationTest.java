package de.fjordkommission.utmbackup.provider.qemu;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.repository.VmStateRepository;
import de.fjordkommission.utmbackup.service.BackupScanner;
import de.fjordkommission.utmbackup.service.BackupService;
import de.fjordkommission.utmbackup.service.CreateBackupService;
import de.fjordkommission.utmbackup.service.RetentionService;
import de.fjordkommission.utmbackup.service.VmChangeStatusService;
import de.fjordkommission.utmbackup.service.VmInventory;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.BackupState;
import de.fjordkommission.utmbackup.storage.BackupStateReader;
import de.fjordkommission.utmbackup.storage.BackupStateResult;
import de.fjordkommission.utmbackup.storage.BackupStateWriter;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.info.BuildProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QEMU through the real format-2 services: writing, scanning, Stable and note state,
 * deletion, retention, change tracking and failure isolation. Only the repositories
 * and the process table are substituted.
 */
class QemuFormatTwoIntegrationTest {

    private static final String ARCH_NAME = "ArchLinux";
    private static final String UTM_PACKAGE = "Linux.utm";

    private static final VmId ARCH = new VmId("qemu", ARCH_NAME);
    private static final VmId FREEBSD = new VmId("qemu", "FreeBSD");
    private static final VmId UTM_LINUX = new VmId("utm", UTM_PACKAGE);

    @TempDir
    Path tempDir;

    private Path backupRoot;
    private Path qemuRoot;
    private Path utmPackage;
    private List<QemuProcess> runningProcesses;

    private StableRepository stableRepository;
    private VmStateRepository stateRepository;
    private VmChangeStatusService changeStatus;
    private BackupStorageGuard guard;
    private CreateBackupService createService;
    private BackupService backupService;
    private RetentionService retentionService;
    private BackupScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        backupRoot = Files.createDirectories(tempDir.resolve("backups"));
        qemuRoot = Files.createDirectories(tempDir.resolve("QEMU").resolve("VMs"));
        writeVm(ARCH_NAME, "arch-linux-disk.qcow2", "arch disk");
        writeVm("FreeBSD", "FreeBSD-15.1.qcow2", "freebsd disk");
        utmPackage = Files.createDirectories(tempDir.resolve("UTM").resolve(UTM_PACKAGE));
        Files.writeString(utmPackage.resolve("config.plist"), "utm config");
        runningProcesses = List.of();

        BackupProperties properties = new BackupProperties(backupRoot, null, null);
        guard = new BackupStorageGuard(properties);
        stableRepository = mock(StableRepository.class);
        when(stableRepository.findAll()).thenReturn(Map.of());
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(1);

        stateRepository = mock(VmStateRepository.class);
        changeStatus = new VmChangeStatusService(stateRepository);

        BuildProperties buildProperties = mock(BuildProperties.class);
        createService = new CreateBackupService(properties, changeStatus, guard, buildProperties);

        scanner = scannerFor(List.of(utmProvider(), qemuProvider(() -> runningProcesses)), settings);
        backupService = new BackupService(scanner, stableRepository, guard, new BackupStateWriter());
        retentionService = new RetentionService(scanner, backupService, guard);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    @Test
    void stoppedQemuVmIsWrittenAsFormatTwoContainerWithCanonicalIdentity() throws IOException {
        CreateBackupService.Result result = createService.create(List.of(stopped(ARCH)), "qemu backup");

        BackupManifest manifest = manifestOf(result.backupDirectory());
        assertEquals(BackupFormat.PROVIDER_AWARE, manifest.formatVersion());
        assertEquals(1, manifest.vms().size());
        BackupManifest.VmEntry entry = manifest.vms().getFirst();
        assertEquals(ARCH, entry.id());
        assertEquals(ARCH_NAME, entry.name());
        assertEquals("qemu__ArchLinux", entry.directory());

        Path copiedDisk = result.backupDirectory().resolve("VMs/qemu__ArchLinux/ArchLinux/arch-linux-disk.qcow2");
        assertEquals("arch disk", Files.readString(copiedDisk));
    }

    @Test
    void successfulBackupAcknowledgesTheCanonicalQemuId() {
        createService.create(List.of(stopped(ARCH)), null);

        verify(stateRepository).save(eq(ARCH), anyLong(), eq(VmStateRepository.Acknowledgement.BACKED_UP));
    }

    @Test
    void runningQemuVmIsRejectedBeforeAnythingIsCopied() {
        runningProcesses = List.of(processReferencing(qemuRoot.resolve(ARCH_NAME)));
        ManagedVm running = inventory().build().find(ARCH).orElseThrow();
        assertEquals(VmStatus.RUNNING, running.status());

        List<ManagedVm> batch = List.of(running);

        assertThrows(IllegalArgumentException.class, () -> createService.create(batch, null));

        assertNoBatchWritten();
    }

    @Test
    void unknownQemuVmIsRejectedBeforeAnythingIsCopied() {
        runningProcesses = List.of(new QemuProcess("/opt/homebrew/bin/qemu-system-aarch64", null));
        ManagedVm unknown = inventory().build().find(ARCH).orElseThrow();
        assertEquals(VmStatus.UNKNOWN, unknown.status());

        List<ManagedVm> batch = List.of(unknown);

        assertThrows(IllegalArgumentException.class, () -> createService.create(batch, null));

        assertNoBatchWritten();
    }

    @Test
    void utmAndQemuVmsShareOneBatchWithSeparateContainers() {
        CreateBackupService.Result result = createService.create(
                List.of(stopped(UTM_LINUX), stopped(ARCH)), null);

        BackupManifest manifest = manifestOf(result.backupDirectory());
        assertEquals(List.of(UTM_LINUX, ARCH),
                manifest.vms().stream().map(BackupManifest.VmEntry::id).toList());
        String utmContainer = manifest.vms().getFirst().directory();
        assertTrue(Files.isRegularFile(result.backupDirectory().resolve("VMs").resolve(utmContainer).resolve("Linux.utm/config.plist")));
        assertTrue(Files.isRegularFile(result.backupDirectory().resolve("VMs/qemu__ArchLinux/ArchLinux/arch-linux-disk.qcow2")));
        assertEquals("qemu__ArchLinux", manifest.vms().get(1).directory());
    }

    // ------------------------------------------------------------------
    // Scanning and provider visibility
    // ------------------------------------------------------------------

    @Test
    void backupIsScannedAsTheLocalQemuVmOfTheEnabledProvider() {
        createService.create(List.of(stopped(ARCH)), null);

        VmOverview vm = find(scanner.scan(), ARCH);

        assertTrue(vm.local());
        assertEquals("QEMU", vm.providerName());
        assertEquals(VmStatus.STOPPED, vm.status());
        assertEquals(1, vm.backupCount());
        assertTrue(scanner.scan().providerEnabled("qemu"));
    }

    @Test
    void qemuBackupStaysVisibleWhenTheQemuProviderIsDisabled() {
        createService.create(List.of(stopped(ARCH)), null);

        BackupScanner utmOnly = scannerFor(List.of(utmProvider()), mock(SettingsRepository.class));
        VmOverview vm = find(utmOnly.scan(), ARCH);

        assertFalse(vm.local());
        assertEquals("qemu", vm.providerName());
        assertEquals(1, vm.backupCount());
        assertFalse(utmOnly.scan().providerEnabled("qemu"));
    }

    @Test
    void changeTrackingUsesTheCanonicalIdAndTheQemuSourceDirectory() throws IOException {
        createService.create(List.of(stopped(ARCH)), null);
        ArgumentCaptor<Long> acknowledged = ArgumentCaptor.forClass(Long.class);
        verify(stateRepository).save(eq(ARCH), acknowledged.capture(), any());
        when(stateRepository.find(ARCH)).thenReturn(Optional.of(
                new VmStateRepository.State(acknowledged.getValue(), VmStateRepository.Acknowledgement.BACKED_UP, "")));

        assertEquals(VmChangeStatus.UNCHANGED, find(scanner.scan(), ARCH).changeStatus());

        Path disk = qemuRoot.resolve(ARCH_NAME).resolve("arch-linux-disk.qcow2");
        Files.setLastModifiedTime(disk, FileTime.from(Instant.now().plusSeconds(3600)));

        assertEquals(VmChangeStatus.CHANGED, find(scanner.scan(), ARCH).changeStatus());
    }

    // ------------------------------------------------------------------
    // Stable, note, delete and retention
    // ------------------------------------------------------------------

    @Test
    void stableQemuBackupStoresItsStateInTheContainerAndBlocksDelete() {
        CreateBackupService.Result result = createService.create(List.of(stopped(ARCH)), null);
        String id = backupIdOf(scanner.scan(), ARCH);

        backupService.stable(id, true);

        Path container = result.backupDirectory().resolve("VMs/qemu__ArchLinux");
        assertTrue(Files.isRegularFile(container.resolve("BACKUP-STATE.properties")));
        assertTrue(find(scanner.scan(), ARCH).backups().getFirst().stable());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> backupService.delete(id));
        assertTrue(error.getMessage().contains("Stable backups cannot be deleted"), error.getMessage());
        assertTrue(Files.isDirectory(container));
    }

    @Test
    void noteOfQemuBackupIsStoredInItsStateFile() {
        CreateBackupService.Result result = createService.create(List.of(stopped(ARCH)), null);
        String id = backupIdOf(scanner.scan(), ARCH);

        backupService.note(id, "before the kernel update");

        Path container = result.backupDirectory().resolve("VMs/qemu__ArchLinux");
        assertEquals(
                new BackupStateReader().read(container),
                new BackupStateResult.Parsed(new BackupState(false, "before the kernel update"))
        );
    }

    @Test
    void retentionRemovesOnlyTheUnprotectedQemuBackup() {
        createService.create(List.of(stopped(ARCH)), null);
        createService.create(List.of(stopped(ARCH)), null);
        createService.create(List.of(stopped(ARCH)), null);
        List<String> backups = backupIdsOldestFirst(scanner.scan(), ARCH);
        backupService.stable(backups.getFirst(), true);

        retentionService.deleteBackupsExceedingRetention(List.of(ARCH));

        List<String> remaining = backupIdsOldestFirst(scanner.scan(), ARCH);
        assertEquals(List.of(backups.getFirst(), backups.getLast()), remaining);
        assertEquals(2, find(scanner.scan(), ARCH).backupCount());
    }

    // ------------------------------------------------------------------
    // Failure isolation
    // ------------------------------------------------------------------

    @Test
    void missingQemuRootDoesNotBreakUtmOrExistingQemuBackups() {
        createService.create(List.of(stopped(ARCH)), null);
        BackupScanner broken = scannerFor(List.of(
                utmProvider(),
                new QemuVmProvider(tempDir.resolve("no-such-qemu-root"), List::of)
        ), mock(SettingsRepository.class));

        BackupOverview overview = broken.scan();

        assertTrue(overview.providerHealthy("utm"));
        assertFalse(overview.providerHealthy("qemu"));
        assertEquals(VmStatus.STOPPED, find(overview, UTM_LINUX).status());
        assertEquals(1, find(overview, ARCH).backupCount());
    }

    @Test
    void failingProcessInspectionMarksOnlyQemuVmsUnknown() {
        BackupScanner inspectionFails = scannerFor(List.of(
                utmProvider(),
                new QemuVmProvider(qemuRoot, () -> {
                    throw new IllegalStateException("process table unavailable");
                })
        ), mock(SettingsRepository.class));

        BackupOverview overview = inspectionFails.scan();

        assertEquals(VmStatus.STOPPED, find(overview, UTM_LINUX).status());
        assertEquals(VmStatus.UNKNOWN, find(overview, ARCH).status());
        assertEquals(VmStatus.UNKNOWN, find(overview, FREEBSD).status());
        assertFalse(overview.providerHealthy("qemu"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void writeVm(String name, String disk, String content) throws IOException {
        Path directory = Files.createDirectories(qemuRoot.resolve(name));
        Files.writeString(directory.resolve(disk), content);
    }

    private FakeVmProvider utmProvider() {
        return FakeVmProvider.of(
                "utm",
                List.of(new LocalVm(UTM_PACKAGE, "Linux", List.of(utmPackage))),
                Map.of(UTM_PACKAGE, VmStatus.STOPPED)
        );
    }

    private QemuVmProvider qemuProvider(Supplier<List<QemuProcess>> processes) {
        return new QemuVmProvider(qemuRoot, processes);
    }

    private BackupScanner scannerFor(List<VmProvider> providers, SettingsRepository settings) {
        List<String> ids = providers.stream().map(VmProvider::providerId).toList();
        VmInventory inventory = new VmInventory(new VmProviderRegistry(
                providers,
                new VmProperties(ids, null)
        ));

        return new BackupScanner(
                new BackupProperties(backupRoot, null, null),
                stableRepository,
                settings,
                inventory,
                changeStatus,
                guard
        );
    }

    private VmInventory inventory() {
        return new VmInventory(new VmProviderRegistry(
                List.of(utmProvider(), qemuProvider(() -> runningProcesses)),
                new VmProperties(List.of("utm", "qemu"), null)
        ));
    }

    private ManagedVm stopped(VmId id) {
        return inventory().build().find(id)
                .map(vm -> new ManagedVm(vm.id(), vm.providerName(), vm.localVm(), VmStatus.STOPPED))
                .orElseThrow();
    }

    private QemuProcess processReferencing(Path directory) {
        return new QemuProcess(
                "/opt/homebrew/bin/qemu-system-aarch64",
                List.of("qemu-system-aarch64", "-drive", "file=" + directory + "/arch-linux-disk.qcow2,format=qcow2")
        );
    }

    private void assertNoBatchWritten() {
        try (Stream<Path> entries = Files.list(backupRoot)) {
            assertEquals(List.of(), entries.toList());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private BackupManifest manifestOf(Path batch) {
        BackupManifestResult result = new BackupManifestReader().read(batch);
        assertInstanceOf(BackupManifestResult.Parsed.class, result, String.valueOf(result));
        return ((BackupManifestResult.Parsed) result).manifest();
    }

    private static VmOverview find(BackupOverview overview, VmId id) {
        return overview.vms().stream()
                .filter(vm -> vm.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry for " + id));
    }

    private static String backupIdOf(BackupOverview overview, VmId id) {
        return backupIdsOldestFirst(overview, id).getFirst();
    }

    private static List<String> backupIdsOldestFirst(BackupOverview overview, VmId id) {
        return find(overview, id).backups().stream()
                .sorted(Comparator.comparing(VmBackup::backupTime))
                .map(VmBackup::id)
                .toList();
    }
}
