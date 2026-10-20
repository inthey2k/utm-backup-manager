package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Scanner behaviour with several enabled providers and format-1 backups.
 */
class BackupScannerMultiProviderTest {

    @TempDir
    Path tempDir;

    private Path root;
    private VmChangeStatusService changeStatusService;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("backups");
        Files.createDirectories(root);
        changeStatusService = mock(VmChangeStatusService.class);
        when(changeStatusService.determine(anyString(), any(), anyBoolean()))
                .thenReturn(VmChangeStatus.UNKNOWN);
    }

    @Test
    void utmAndNonUtmLocalVmsAppearTogetherAndLegacyBackupsAttachToUtmOnly() throws IOException {
        // Legacy folder "ArchLinux" belongs to utm:ArchLinux, not to qemu:ArchLinux,
        // even though both providers report the same local id.
        createLegacyBatch("2026-09-01_1200", "ArchLinux");

        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(localVm("ArchLinux", "ArchLinux"), localVm("Linux.utm", "Linux")),
                Map.of("ArchLinux", VmStatus.STOPPED, "Linux.utm", VmStatus.STOPPED)
        );
        FakeVmProvider qemu = FakeVmProvider.of(
                "qemu",
                List.of(localVm("ArchLinux", "ArchLinux")),
                Map.of("ArchLinux", VmStatus.STOPPED)
        );

        BackupOverview overview = scanner(utm, qemu).scan();

        VmOverview utmArch = find(overview, new VmId("utm", "ArchLinux"));
        VmOverview qemuArch = find(overview, new VmId("qemu", "ArchLinux"));
        VmOverview utmLinux = find(overview, new VmId("utm", "Linux.utm"));

        assertEquals(1, utmArch.backupCount());
        assertEquals(0, qemuArch.backupCount());
        assertEquals(0, utmLinux.backupCount());

        assertEquals("UTM (fake)", utmArch.providerName());
        assertEquals("QEMU (fake)", qemuArch.providerName());
        assertTrue(qemuArch.local());
        assertEquals(VmChangeStatus.NEVER_BACKED_UP, qemuArch.changeStatus());
    }

    @Test
    void legacyBackupRemainsAttributedToUtmWhenUtmIsNotEnabled() throws IOException {
        createLegacyBatch("2026-09-01_1200", "Old.utm");

        FakeVmProvider qemu = FakeVmProvider.of("qemu", List.of(), Map.of());

        BackupOverview overview = scanner(qemu).scan();

        VmOverview backupOnly = find(overview, new VmId("utm", "Old.utm"));
        assertFalse(backupOnly.local());
        assertEquals("utm", backupOnly.providerId());
        assertEquals("utm", backupOnly.providerName());
        assertEquals(VmStatus.UNKNOWN, backupOnly.status());
        assertEquals(1, backupOnly.backupCount());
    }

    @Test
    void failingProviderDoesNotHideOtherProvidersOrBackups() throws IOException {
        createLegacyBatch("2026-09-01_1200", "Linux.utm");

        FakeVmProvider broken = FakeVmProvider.listingFails("qemu", new IllegalStateException("qemu unavailable"));
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(localVm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );

        BackupOverview overview = scanner(broken, utm).scan();

        VmOverview linux = find(overview, new VmId("utm", "Linux.utm"));
        assertEquals(1, linux.backupCount());
        assertEquals(VmStatus.STOPPED, linux.status());

        assertTrue(overview.providers().stream()
                .anyMatch(snapshot -> snapshot.providerId().equals("qemu")
                        && snapshot.errors().getFirst().contains("qemu unavailable")));
    }

    @Test
    void providersAreQueriedOncePerScan() {
        FakeVmProvider utm = FakeVmProvider.of("utm", List.of(localVm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED));
        FakeVmProvider qemu = FakeVmProvider.of("qemu", List.of(), Map.of());

        scanner(utm, qemu).scan();

        assertEquals(1, utm.listCalls());
        assertEquals(1, utm.statusCalls());
        assertEquals(1, qemu.listCalls());
        assertEquals(1, qemu.statusCalls());
    }

    private BackupScanner scanner(FakeVmProvider... providers) {
        VmProviderRegistry registry = new VmProviderRegistry(
                List.of(providers),
                new VmProperties(
                        java.util.Arrays.stream(providers).map(FakeVmProvider::providerId).toList(),
                        null,
                        null
                )
        );

        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(3);

        return new BackupScanner(
                new BackupProperties(root, tempDir.resolve("metadata.db"), tempDir.resolve("state.tsv")),
                mock(StableRepository.class),
                settings,
                new VmInventory(registry),
                changeStatusService
        );
    }

    private static LocalVm localVm(String id, String name) {
        return new LocalVm(id, name, List.of(Path.of("/vms", id)));
    }

    private void createLegacyBatch(String directoryName, String folder) throws IOException {
        Files.createDirectories(root.resolve(directoryName).resolve("VMs").resolve(folder));
    }

    private static VmOverview find(BackupOverview overview, VmId id) {
        return overview.vms().stream()
                .filter(vm -> vm.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry for " + id));
    }
}
