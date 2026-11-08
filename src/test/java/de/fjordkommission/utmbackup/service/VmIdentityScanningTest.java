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
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmIdentitySidecar;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupIdentity;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.VmIdentityFile;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Identity migration seen from the backup overview. A VM keeps its UTM UUID while its
 * package is renamed, so backups under old package names are attributed to the same VM.
 * Backup ids, stable flags and notes stay unchanged, and nothing is deleted or moved.
 */
class VmIdentityScanningTest {

    private static final String UUID = "6C1F2A3B-0000-4000-8000-00000000A001";
    private static final String OLD_PACKAGE = "Linux Mint 23.utm";
    private static final String NEW_PACKAGE = "Linux Mint 27.utm";
    private static final String OLD_BATCH = "2026-09-01_1200";
    private static final String NEW_BATCH = "2026-10-01_1200";
    private static final OffsetDateTime TIME = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.ofHours(2));

    @TempDir
    Path tempDir;

    private Path root;
    private Path localPackage;
    private StableRepository stable;
    private BackupStorageGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tempDir.resolve("backups"));
        localPackage = Files.createDirectories(tempDir.resolve("vms").resolve(NEW_PACKAGE));
        Files.writeString(localPackage.resolve("config.plist"), plist(UUID));
        stable = mock(StableRepository.class);
        when(stable.findAll()).thenReturn(Map.of());
        guard = mock(BackupStorageGuard.class);
        when(guard.isWritable()).thenReturn(true);
    }

    // ------------------------------------------------------------------
    // Attribution of historical backups
    // ------------------------------------------------------------------

    @Test
    void historicalPackagesSharingAUuidMergeIntoOneLogicalVmWithItsStableFlags() throws IOException {
        formatOneBatch(OLD_BATCH, OLD_PACKAGE, UUID);
        formatOneBatch(NEW_BATCH, NEW_PACKAGE, UUID);
        when(stable.findAll()).thenReturn(Map.of(
                BackupIdentity.legacyId(OLD_BATCH, OLD_PACKAGE), new StableRepository.Meta(true, "Before kernel")));

        BackupOverview overview = scanner(3).scan();

        assertEquals(1, overview.vms().stream().filter(vm -> vm.id().localId().equals(UUID)).count());
        assertTrue(overview.vms().stream().noneMatch(vm -> vm.id().localId().equals(OLD_PACKAGE)),
                "the old package name must not be a separate VM");

        VmOverview vm = find(overview, new VmId("utm", UUID));
        assertTrue(vm.local());
        assertEquals("Linux Mint 27", vm.name());
        assertEquals(List.of(NEW_BATCH, OLD_BATCH),
                vm.backups().stream().map(VmBackup::backupDirectoryName).toList(),
                "one history, newest first");

        VmBackup stableBackup = vm.backups().get(1);
        assertTrue(stableBackup.stable());
        assertEquals("Before kernel", stableBackup.note());
        assertEquals(BackupIdentity.legacyId(OLD_BATCH, OLD_PACKAGE), stableBackup.id(),
                "backup ids are never recalculated");
    }

    @Test
    void retentionIsCalculatedOnTheMergedHistory() throws IOException {
        formatOneBatch(OLD_BATCH, OLD_PACKAGE, UUID);
        formatOneBatch(NEW_BATCH, NEW_PACKAGE, UUID);

        BackupOverview overview = scanner(1).scan();

        assertTrue(overview.retentionExceeded().contains(new VmId("utm", UUID)),
                "two normal backups exceed a limit of one only when they are one history");
        assertEquals(Set.of(BackupIdentity.legacyId(OLD_BATCH, OLD_PACKAGE)),
                overview.retentionCandidatesForDeletion(),
                "only the oldest backup is a candidate, and it is only marked");
    }

    @Test
    void formatTwoBackupOfAnOldPackageNameIsAttributedThroughItsContainerUuid() throws IOException {
        formatTwoBatch("2026-10-02_1200", entry(OLD_PACKAGE), OLD_PACKAGE, UUID);

        BackupOverview overview = scanner(3).scan();

        assertEquals(1, find(overview, new VmId("utm", UUID)).backupCount());
        assertTrue(overview.vms().stream().noneMatch(vm -> vm.id().localId().equals(OLD_PACKAGE)));
        VmIdentityRegistry registry = ((VmIdentityFile.ReadResult.Loaded) VmIdentityFile.read(root)).registry();
        assertEquals(Optional.of(new VmId("utm", UUID)), registry.canonicalOf(new VmId("utm", OLD_PACKAGE)),
                "the scan records the historical name as an alias");
    }

    @Test
    void formatTwoBackupWithoutReadableConfigUsesTheRecordedAlias() throws IOException {
        VmIdentityFile.write(root, VmIdentityRegistry.empty().observe(new VmIdentityRegistry.Observation(
                new VmId("utm", UUID),
                VmIdentityRegistry.IdentitySource.PROVIDER_NATIVE,
                OLD_PACKAGE,
                "Linux Mint 23",
                false)));
        formatTwoBatch("2026-10-02_1200", entry(OLD_PACKAGE), OLD_PACKAGE, null);

        BackupOverview overview = scanner(3).scan();

        assertEquals(1, find(overview, new VmId("utm", UUID)).backupCount());
    }

    @Test
    void backupWithoutNativeIdOrAliasStaysUnderItsStoredIdentity() throws IOException {
        formatTwoBatch("2026-10-02_1200", entry(OLD_PACKAGE), OLD_PACKAGE, null);

        BackupOverview overview = scanner(3).scan();

        VmOverview unresolved = find(overview, new VmId("utm", OLD_PACKAGE));
        assertFalse(unresolved.local(), "attribution is never guessed, so the backup is kept apart");
        assertEquals(1, unresolved.backupCount());
    }

    @Test
    void qemuBackupsKeepTheirLocalIdentityAndWriteNoRegistry() throws IOException {
        Path qemuBatch = Files.createDirectories(root.resolve("2026-10-03_1200"));
        Path container = Files.createDirectories(qemuBatch.resolve("VMs").resolve("qemu__Ubuntu"));
        Files.writeString(container.resolve("payload.txt"), "qemu");
        new BackupManifestWriter().writeFile(qemuBatch, new BackupManifest(
                BackupFormat.PROVIDER_AWARE, Optional.of(TIME), "qemu",
                List.of(new BackupManifest.VmEntry(new VmId("qemu", "Ubuntu"), "Ubuntu", "qemu__Ubuntu"))));
        FakeVmProvider qemu = FakeVmProvider.of("qemu",
                List.of(new LocalVm("Ubuntu", "Ubuntu", List.of(tempDir.resolve("qemu").resolve("Ubuntu")))),
                Map.of("Ubuntu", VmStatus.STOPPED));

        BackupOverview overview = scanner(3, qemu).scan();

        assertEquals(1, find(overview, new VmId("qemu", "Ubuntu")).backupCount());
        boolean qemuRecorded = VmIdentityFile.read(root) instanceof VmIdentityFile.ReadResult.Loaded(
                VmIdentityRegistry registry
        )
                && registry.identities().stream().anyMatch(entry -> entry.providerId().equals("qemu"));
        assertFalse(qemuRecorded, "without a native id nothing is recorded, so QEMU behaviour is unchanged");
    }

    @Test
    void qemuVmRenamedAfterItsBackupKeepsTheHistoryThroughItsGeneratedId() throws IOException {
        Path qemuRoot = Files.createDirectories(tempDir.resolve("qemu"));
        Path original = Files.createDirectories(qemuRoot.resolve("Ubuntu"));
        Files.writeString(original.resolve("ubuntu.qcow2"), "disk");
        String generated = VmIdentitySidecar.readOrCreate(original, "qemu").orElseThrow();

        // A backup made under the old directory name; its container copy has no identity file.
        formatTwoBatch("2026-09-05_1200", new BackupManifest.VmEntry(
                new VmId("qemu", "Ubuntu"), "Ubuntu", "qemu__Ubuntu"), "Ubuntu", null);
        scanner(3, qemuVm(generated, "Ubuntu", original)).scan(); // records the old name as an alias

        Path renamed = Files.move(original, qemuRoot.resolve("Ubuntu 26"));
        BackupOverview overview = scanner(3, qemuVm(generated, "Ubuntu 26", renamed)).scan();

        VmOverview vm = find(overview, new VmId("qemu", generated));
        assertEquals("Ubuntu 26", vm.name());
        assertEquals(1, vm.backupCount(), "the pre-rename backup stays with the VM");
        assertTrue(overview.vms().stream().noneMatch(entry -> entry.id().equals(new VmId("qemu", "Ubuntu"))),
                "the old directory name is not a separate VM");
    }

    private static FakeVmProvider qemuVm(String generatedId, String name, Path directory) {
        return FakeVmProvider.of("qemu",
                List.of(new LocalVm(generatedId, name, List.of(directory))),
                Map.of(generatedId, VmStatus.STOPPED));
    }

    // ------------------------------------------------------------------
    // Registry handling and data safety
    // ------------------------------------------------------------------

    @Test
    void scanRecordsTheCurrentVmAndIsIdempotent() throws IOException {
        formatOneBatch(OLD_BATCH, OLD_PACKAGE, UUID);
        scanner(3).scan();

        Path registry = root.resolve(VmIdentityFile.FILE_NAME);
        String first = Files.readString(registry);
        var firstTime = Files.getLastModifiedTime(registry);

        scanner(3).scan();

        assertEquals(first, Files.readString(registry), "an unchanged registry is not rewritten");
        assertEquals(firstTime, Files.getLastModifiedTime(registry));
        assertTrue(first.contains(NEW_PACKAGE), "the current package is recorded as the current locator");
    }

    @Test
    void scanNeverDeletesOrMovesBackupData() throws IOException {
        formatOneBatch(OLD_BATCH, OLD_PACKAGE, UUID);
        formatTwoBatch("2026-10-02_1200", entry(OLD_PACKAGE), OLD_PACKAGE, UUID);
        Set<String> before = listing();

        scanner(1).scan();

        assertEquals(before, listing(), "identity bookkeeping may only add the registry file");
    }

    @Test
    void unusableRegistryIsReportedNotOverwrittenAndDoesNotBreakTheScan() throws IOException {
        formatOneBatch(OLD_BATCH, OLD_PACKAGE, UUID);
        Path registry = root.resolve(VmIdentityFile.FILE_NAME);
        Files.writeString(registry, "{ damaged");

        BackupOverview overview = scanner(3).scan();

        assertEquals(1, find(overview, new VmId("utm", UUID)).backupCount(),
                "native ids still attribute the backup");
        assertEquals("{ damaged", Files.readString(registry));
    }

    @Test
    void unusableRegistryFallsBackToStoredIdsForAliasedBackups() throws IOException {
        Path registry = root.resolve(VmIdentityFile.FILE_NAME);
        Files.writeString(registry, "{\"schemaVersion\":9,\"identities\":[]}");
        formatTwoBatch("2026-10-02_1200", entry(OLD_PACKAGE), OLD_PACKAGE, null);

        BackupOverview overview = scanner(3).scan();

        assertFalse(find(overview, new VmId("utm", OLD_PACKAGE)).local());
        assertEquals("{\"schemaVersion\":9,\"identities\":[]}", Files.readString(registry));
    }

    @Test
    void readOnlyStorageWritesNoRegistry() throws IOException {
        when(guard.isWritable()).thenReturn(false);
        formatOneBatch(OLD_BATCH, OLD_PACKAGE, UUID);

        BackupOverview overview = scanner(3).scan();

        assertEquals(1, find(overview, new VmId("utm", UUID)).backupCount());
        assertFalse(Files.exists(root.resolve(VmIdentityFile.FILE_NAME)));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private BackupScanner scanner(int retentionLimit, FakeVmProvider... extra) {
        FakeVmProvider utm = FakeVmProvider.of("utm",
                List.of(new LocalVm(UUID, "Linux Mint 27", List.of(localPackage))),
                Map.of(UUID, VmStatus.STOPPED));

        List<VmProvider> providers = new java.util.ArrayList<>();
        providers.add(utm);
        providers.addAll(List.of(extra));

        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getRetentionLimit()).thenReturn(retentionLimit);

        VmChangeStatusService changeStatus = mock(VmChangeStatusService.class);
        when(changeStatus.determine(any(VmId.class), any(), anyBoolean())).thenReturn(VmChangeStatus.UNKNOWN);

        return new BackupScanner(
                new BackupProperties(root, null, null),
                stable,
                settings,
                new VmInventory(new VmProviderRegistry(providers,
                        new VmProperties(providers.stream().map(VmProvider::providerId).toList(), null))),
                changeStatus,
                guard
        );
    }

    /** A format-1 batch: {@code VMs/<folder>/} with an optional {@code config.plist}. */
    private void formatOneBatch(String batch, String folder, String uuidOrNull) throws IOException {
        Path package_ = Files.createDirectories(root.resolve(batch).resolve("VMs").resolve(folder));
        Files.writeString(package_.resolve("payload.txt"), folder);

        if (uuidOrNull != null) {
            Files.writeString(package_.resolve("config.plist"), plist(uuidOrNull));
        }
    }

    /** A format-2 batch whose container holds one package, with an optional {@code config.plist}. */
    private void formatTwoBatch(String batch, BackupManifest.VmEntry entry, String packageName, String uuidOrNull)
            throws IOException {
        Path batchDirectory = Files.createDirectories(root.resolve(batch));
        Path container = Files.createDirectories(batchDirectory.resolve("VMs").resolve(entry.directory()));
        Path package_ = Files.createDirectories(container.resolve(packageName));
        Files.writeString(package_.resolve("payload.txt"), packageName);

        if (uuidOrNull != null) {
            Files.writeString(package_.resolve("config.plist"), plist(uuidOrNull));
        }

        new BackupManifestWriter().writeFile(batchDirectory, new BackupManifest(
                BackupFormat.PROVIDER_AWARE, Optional.of(TIME), "comment", List.of(entry)));
    }

    private static BackupManifest.VmEntry entry(String packageName) {
        return new BackupManifest.VmEntry(
                new VmId("utm", packageName),
                packageName.replace(".utm", ""),
                "utm__" + packageName.replace(".utm", "").replace(' ', '-'));
    }

    private static String plist(String uuid) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict><key>UUID</key><string>%s</string></dict></plist>
                """.formatted(uuid);
    }

    /** Relative paths of every file and directory under the backup root. */
    private Set<String> listing() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> !path.equals(root.resolve(VmIdentityFile.FILE_NAME)))
                    .map(path -> root.relativize(path).toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static VmOverview find(BackupOverview overview, VmId id) {
        return overview.vms().stream()
                .filter(vm -> vm.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No overview entry for " + id));
    }
}
