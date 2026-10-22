package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupIdentity;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Scanning of format-2 batches: identity from the manifest, containers resolved from
 * the manifest, and coexistence with format-1 batches.
 */
class FormatTwoScanningTest {

    private static final OffsetDateTime TIME = OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.ofHours(2));

    @TempDir
    Path root;

    private StableRepository repo;
    private FakeVmProvider utm;
    private FakeVmProvider qemu;

    @BeforeEach
    void setUp() {
        repo = mock(StableRepository.class);
        when(repo.findAll()).thenReturn(Map.of());
        utm = FakeVmProvider.of("utm",
                List.of(new LocalVm("Linux.utm", "Linux", List.of(Path.of("/vms", "Linux.utm")))),
                Map.of("Linux.utm", VmStatus.STOPPED));
        qemu = FakeVmProvider.of("qemu", List.of(), Map.of());
    }

    private BackupScanner scanner(FakeVmProvider... providers) {
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);
        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        when(changeStatus.determine(any(VmId.class), any(), anyBoolean())).thenReturn(VmChangeStatus.UNKNOWN);

        return new BackupScanner(
                new BackupProperties(root, null, null),
                repo,
                settings,
                new VmInventory(new VmProviderRegistry(List.of(providers),
                        new VmProperties(java.util.Arrays.stream(providers).map(FakeVmProvider::providerId).toList(), null))),
                changeStatus,
                mock(BackupStorageGuard.class)
        );
    }

    /** Creates a format-2 batch: its manifest and one container with a payload per entry. */
    private Path formatTwoBatch(String name, String comment, BackupManifest.VmEntry... entries) throws IOException {
        Path batch = Files.createDirectories(root.resolve(name));

        for (BackupManifest.VmEntry entry : entries) {
            Path container = Files.createDirectories(batch.resolve("VMs").resolve(entry.directory()));
            Files.writeString(container.resolve("payload.txt"), entry.id().toString());
        }

        new BackupManifestWriter().writeFile(batch, new BackupManifest(
                BackupFormat.PROVIDER_AWARE, Optional.of(TIME), comment, List.of(entries)));

        return batch;
    }

    private static BackupManifest.VmEntry entry(String provider, String localId, String name, String directory) {
        return new BackupManifest.VmEntry(new VmId(provider, localId), name, directory);
    }

    private void legacyBatch(String name) throws IOException {
        Files.createDirectories(root.resolve(name).resolve("VMs").resolve("Linux.utm"));
    }

    private static VmOverview find(BackupOverview overview, VmId id) {
        return overview.vms().stream()
                .filter(vm -> vm.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry for " + id));
    }

    @Test
    void formatTwoBackupAttachesToTheLocalVmThroughItsCanonicalIdAndManifestData() throws IOException {
        String name = "2026-10-20_1200";
        formatTwoBatch(name, "Before kernel update",
                entry("utm", "Linux.utm", "Linux", "utm__Linux"));

        VmOverview linux = find(scanner(utm).scan(), new VmId("utm", "Linux.utm"));

        assertTrue(linux.local());
        assertEquals(1, linux.backupCount());
        VmBackup backup = linux.backups().getFirst();
        assertEquals(BackupIdentity.formatTwoId(name, new VmId("utm", "Linux.utm")), backup.id());
        assertEquals(name, backup.backupDirectoryName());
        assertEquals("Before kernel update", backup.comment());
        assertEquals("Linux", backup.vmName());
        assertEquals(root.resolve(name).resolve("VMs").resolve("utm__Linux"), backup.vmPath());
        assertEquals(TIME.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(), backup.backupTime());
    }

    @Test
    void backupOnlyFormatTwoVmAppearsWithItsProviderAndUnknownStatus() throws IOException {
        formatTwoBatch("2026-10-20_1200", "qemu only",
                entry("qemu", "ArchLinux", "Arch Linux", "qemu__ArchLinux"));

        VmOverview vm = find(scanner(utm, qemu).scan(), new VmId("qemu", "ArchLinux"));

        assertFalse(vm.local());
        assertEquals("qemu", vm.providerId());
        assertEquals("QEMU (fake)", vm.providerName(), "the enabled provider's display name is shown");
        assertEquals(VmStatus.UNKNOWN, vm.status());
        assertEquals(1, vm.backupCount());
        assertEquals("Arch Linux", vm.name());
    }

    @Test
    void containerNameIsNotAuthoritativeTheManifestIdentityWins() throws IOException {
        // The folder name suggests a different VM. The manifest decides.
        Path batch = formatTwoBatch("2026-10-20_1200", "c",
                entry("utm", "Foo.utm", "Foo", "x__weird"));
        Files.createDirectories(batch.resolve("VMs").resolve("utm__Bar"));

        BackupOverview overview = scanner(utm).scan();

        VmOverview foo = find(overview, new VmId("utm", "Foo.utm"));
        assertEquals(1, foo.backupCount());
        assertTrue(foo.backups().getFirst().vmPath().endsWith("x__weird"));
        assertTrue(overview.vms().stream().noneMatch(vm -> vm.id().localId().equals("utm__Bar")),
                "a container the manifest does not list is never a backup");
    }

    @Test
    void sameLocalIdUnderDifferentProvidersIsTwoDistinctVmsWithDistinctBackups() throws IOException {
        formatTwoBatch("2026-10-20_1200", "both",
                entry("utm", "ArchLinux", "ArchLinux", "utm__ArchLinux"),
                entry("qemu", "ArchLinux", "ArchLinux", "qemu__ArchLinux"));

        BackupOverview overview = scanner(utm, qemu).scan();

        VmOverview utmArch = find(overview, new VmId("utm", "ArchLinux"));
        VmOverview qemuArch = find(overview, new VmId("qemu", "ArchLinux"));
        assertEquals(1, utmArch.backupCount());
        assertEquals(1, qemuArch.backupCount());
        assertNotEquals(utmArch.backups().getFirst().id(), qemuArch.backups().getFirst().id());
    }

    @Test
    void mixedFormatMediumScansBothFormatsIndependently() throws IOException {
        legacyBatch("2026-09-01_1200");
        formatTwoBatch("2026-10-20_1200", "new", entry("utm", "Linux.utm", "Linux", "utm__Linux"));

        VmOverview linux = find(scanner(utm).scan(), new VmId("utm", "Linux.utm"));

        assertEquals(2, linux.backupCount());
        assertTrue(linux.backups().stream().anyMatch(b -> b.id().equals(
                BackupIdentity.legacyId("2026-09-01_1200", "Linux.utm"))));
        assertTrue(linux.backups().stream().anyMatch(b -> b.id().equals(
                BackupIdentity.formatTwoId("2026-10-20_1200", new VmId("utm", "Linux.utm")))));
    }

    @Test
    void missingContainerIsReportedAndTheOtherEntriesStillShow() throws IOException {
        Path batch = formatTwoBatch("2026-10-20_1200", "partly",
                entry("utm", "Linux.utm", "Linux", "utm__Linux"),
                entry("qemu", "ArchLinux", "ArchLinux", "qemu__ArchLinux"));
        Files.delete(batch.resolve("VMs").resolve("qemu__ArchLinux").resolve("payload.txt"));
        Files.delete(batch.resolve("VMs").resolve("qemu__ArchLinux"));

        BackupOverview overview = scanner(utm, qemu).scan();

        assertEquals(1, find(overview, new VmId("utm", "Linux.utm")).backupCount());
        assertTrue(overview.vms().stream().noneMatch(vm -> vm.id().equals(new VmId("qemu", "ArchLinux"))));
        assertTrue(overview.batchIssues().stream().anyMatch(issue ->
                issue.reason().contains("has no container directory qemu__ArchLinux")), overview.batchIssues().toString());
    }

    @Test
    void incompleteTemporaryBatchIsNeverScanned() throws IOException {
        formatTwoBatch(".incomplete-2026-10-20_1200", "half written",
                entry("utm", "Linux.utm", "Linux", "utm__Linux"));

        BackupOverview overview = scanner(utm).scan();

        assertTrue(overview.vms().stream().allMatch(vm -> vm.backups().isEmpty()));
    }
}
