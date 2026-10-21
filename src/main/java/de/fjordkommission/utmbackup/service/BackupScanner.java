package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.*;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.LegacyBackupTime;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Browses the folder on the backup drive for VM backup files in order
 * to display them in the overview page.
 * Local VMs come from {@link VmInventory}, so all enabled providers appear.
 * Format-1 backups are associated with their UTM VM through
 * {@link LegacyBackupLayout}. Their backup ids are unchanged.
 */
@Service
public class BackupScanner {

    private final Path root;
    private final StableRepository repo;
    private final SettingsRepository settings;
    private final VmInventory vmInventory;
    private final VmChangeStatusService vmChangeStatusService;
    private final BackupManifestReader manifestReader = new BackupManifestReader();


    public BackupScanner(
            BackupProperties backupProperties,
            StableRepository repo,
            SettingsRepository settings,
            VmInventory vmInventory,
            VmChangeStatusService vmChangeStatusService
    ) {
        this.root = backupProperties.root();
        this.repo = repo;
        this.settings = settings;
        this.vmInventory = vmInventory;
        this.vmChangeStatusService = vmChangeStatusService;
    }
    public Path root() {
        return root;
    }

    /**
     * Scans local VMs from all enabled providers and existing backups, and
     * groups them by canonical VM identity.
     * Local VMs without backups and backup-only VMs are both included so
     * neither newly created VMs nor historical backups disappear from the UI.
     */
    public BackupOverview scan() {
        int retentionLimit = settings.getRetentionLimit();

        Map<String, StableRepository.Meta> metadata = repo.findAll();
        Map<VmId, List<VmBackup>> backupsByVm = new HashMap<>();
        List<BackupBatchIssue> batchIssues = new ArrayList<>();

        if (!Files.isDirectory(root)) {
            throw new BackupRootUnavailableException(root);
        }

        scanBackupDirectories(backupsByVm, metadata, batchIssues);

        VmInventory.Inventory inventory = vmInventory.build();

        Map<VmId, ManagedVm> localVmsById = new HashMap<>();
        inventory.vms().forEach(vm -> localVmsById.put(vm.id(), vm));

        // Include backup-only VMs so historical backups remain manageable.
        Set<VmId> vmIds = new TreeSet<>(overviewOrder(inventory));
        vmIds.addAll(localVmsById.keySet());
        vmIds.addAll(backupsByVm.keySet());

        Set<VmId> retentionExceeded = new HashSet<>();
        Set<String> retentionCandidatesForDeletion = new HashSet<>();
        List<VmOverview> vms = new ArrayList<>();

        // Build combined list of local VMs and backup-only VMs
        for (VmId vmId : vmIds) {

            ManagedVm localVm = localVmsById.get(vmId);

            String vmName = localVm != null
                    ? localVm.name()
                    : vmId.localId();

            String providerName = localVm != null
                    ? localVm.providerName()
                    : inventory.providerName(vmId.providerId()).orElse(vmId.providerId());

            List<VmBackup> backups =
                    new ArrayList<>(
                            backupsByVm.getOrDefault(
                                    vmId,
                                    List.of()
                            )
                    );

            backups.sort(
                    Comparator.comparing(VmBackup::backupTime).reversed()
            );

            long normalCount = backups.stream()
                    .filter(backup -> !backup.stable())
                    .count();

            if (normalCount > retentionLimit) {
                retentionExceeded.add(vmId);

                long excess = normalCount - retentionLimit;

                backups.stream()
                        .filter(backup -> !backup.stable())
                        .sorted(Comparator.comparing(VmBackup::backupTime))
                        .limit(excess)
                        .map(VmBackup::id)
                        .forEach(retentionCandidatesForDeletion::add);
            }

            VmStatus status = localVm != null
                    ? localVm.status()
                    : VmStatus.UNKNOWN;

            VmChangeStatus changeStatus = changeStatus(vmId, localVm, backups);

            vms.add(
                    new VmOverview(
                            vmId,
                            providerName,
                            vmName,
                            localVm != null ? localVm.localVm() : null,
                            List.copyOf(backups),
                            status,
                            changeStatus
                    )
            );
        }

        return new BackupOverview(
                List.copyOf(vms),
                Set.copyOf(retentionExceeded),
                Set.copyOf(retentionCandidatesForDeletion),
                retentionLimit,
                inventory.providers(),
                List.copyOf(batchIssues)
        );
    }

    /**
     * Finds a backup by its generated ID.
     * The lookup uses the VM-oriented overview so there is only one scanning
     * path for both the UI and backup operations.
     */
    public Optional<VmBackup> find(String id) {
        return scan().vms().stream()
                .flatMap(vm -> vm.backups().stream())
                .filter(backup -> backup.id().equals(id))
                .findFirst();
    }

    /**
     * Change tracking for the legacy UTM keys is unchanged. Other providers do not
     * write {@code vm_state} yet, so their state is UNKNOWN, or NEVER_BACKED_UP
     * when they have no backups.
     * A backup-only VM has no local VM and therefore no state to compare, so it is
     * UNKNOWN. Only a local VM reported as STOPPED is compared.
     */
    private VmChangeStatus changeStatus(
            VmId vmId,
            ManagedVm localVm,
            List<VmBackup> backups
    ) {
        if (localVm == null || localVm.status() != VmStatus.STOPPED) {
            return VmChangeStatus.UNKNOWN;
        }

        if (LegacyBackupLayout.usesLegacyStateKeys(vmId.providerId())) {
            return vmChangeStatusService.determine(
                    vmId.localId(),
                    localVm.localVm(),
                    !backups.isEmpty()
            );
        }

        return backups.isEmpty()
                ? VmChangeStatus.NEVER_BACKED_UP
                : VmChangeStatus.UNKNOWN;
    }

    /**
     * Orders VMs by provider in configured order, then by name. Unknown providers
     * come last, ordered by id.
     */
    private static Comparator<VmId> overviewOrder(VmInventory.Inventory inventory) {
        List<String> providerOrder = inventory.providers().stream()
                .map(ProviderSnapshot::providerId)
                .toList();

        return Comparator
                .comparingInt((VmId id) -> {
                    int rank = providerOrder.indexOf(id.providerId());
                    return rank < 0 ? Integer.MAX_VALUE : rank;
                })
                .thenComparing(VmId::providerId)
                .thenComparing(VmId::localId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(VmId::localId);
    }

    /**
     * Reads all dated backup directories and groups their VM packages by identity.
     */
    private void scanBackupDirectories(
            Map<VmId, List<VmBackup>> backupsByVm,
            Map<String, StableRepository.Meta> metadata,
            List<BackupBatchIssue> batchIssues
    ) {
        try (Stream<Path> directories = Files.list(root)) {
            directories
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName()
                            .toString()
                            .matches("^20\\d\\d-.*"))
                    .forEach(directory ->
                            scanBackupDirectory(
                                    directory,
                                    backupsByVm,
                                    metadata,
                                    batchIssues
                            )
                    );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Backup directory could not be read: " + root,
                    e
            );
        }
    }

    /**
     * Reads the VM backup containers contained in a single backup directory.
     * Each direct subdirectory below "VMs" is a format-1 VM folder and is
     * attributed to its UTM identity.
     */
    private void scanBackupDirectory(
            Path directory,
            Map<VmId, List<VmBackup>> backupsByVm,
            Map<String, StableRepository.Meta> metadata,
            List<BackupBatchIssue> batchIssues
    ) {
        Path vmDirectory = directory.resolve("VMs");

        if (!Files.isDirectory(vmDirectory)) {
            return;
        }

        String directoryName = directory.getFileName().toString();

        Optional<BackupManifest> manifest = legacyManifest(
                directoryName,
                manifestReader.read(directory),
                batchIssues
        );

        if (manifest.isEmpty()) {
            return;
        }

        boolean legacyStable = directoryName
                .toLowerCase(Locale.ROOT)
                .contains("_stable");

        String comment = manifest.get().comment();

        LocalDateTime backupTime = LegacyBackupTime.resolve(directory);

        try (Stream<Path> vmPaths = Files.list(vmDirectory)) {
            vmPaths
                    .filter(Files::isDirectory)
                    .forEach(path -> {
                        String vmFolder = path.getFileName().toString();
                        String id = id(directoryName, vmFolder);

                        StableRepository.Meta storedMetadata =
                                metadata.get(id);

                        boolean stable = storedMetadata != null
                                ? storedMetadata.stable()
                                : legacyStable;

                        String note = storedMetadata != null
                                ? storedMetadata.note()
                                : null;

                        // Preserve Stable information from legacy _stable directories.
                        if (legacyStable && storedMetadata == null) {
                            repo.save(id, true, null);
                        }

                        VmBackup backup = new VmBackup(
                                id,
                                directoryName,
                                vmFolder,
                                path,
                                backupTime,
                                comment,
                                stable,
                                note
                        );

                        backupsByVm
                                .computeIfAbsent(
                                        LegacyBackupLayout.vmIdForFolder(vmFolder),
                                        ignored -> new ArrayList<>()
                                )
                                .add(backup);
                    });

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String id(String directoryName, String vmFolder) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (directoryName + "\n" + vmFolder)
                                .getBytes(StandardCharsets.UTF_8)
                );
    }
    /**
     * Returns the format-1 manifest of a batch that can be shown. Any other batch is
     * recorded with its reason and contributes no VMs or backups, so a manifest that
     * is malformed or from a newer format never leads to guessed identities.
     */
    private static Optional<BackupManifest> legacyManifest(
            String directoryName,
            BackupManifestResult result,
            List<BackupBatchIssue> batchIssues
    ) {
        return switch (result) {
            case BackupManifestResult.Parsed(BackupManifest manifest)
                    when manifest.formatVersion() == BackupFormat.LEGACY ->
                    Optional.of(manifest);
            case BackupManifestResult.Parsed(BackupManifest manifest) -> {
                batchIssues.add(new BackupBatchIssue(
                        directoryName,
                        "Format " + manifest.formatVersion() + " backups are not shown by this version yet"
                ));
                yield Optional.empty();
            }
            case BackupManifestResult.UnsupportedVersion(int formatVersion) -> {
                batchIssues.add(new BackupBatchIssue(
                        directoryName,
                        "Format " + formatVersion + " is newer than the supported format " + BackupFormat.CURRENT
                ));
                yield Optional.empty();
            }
            case BackupManifestResult.Invalid(String reason) -> {
                batchIssues.add(new BackupBatchIssue(directoryName, reason));
                yield Optional.empty();
            }
        };
    }
}
