package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.*;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupIdentity;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.BackupStorageReadOnlyException;
import de.fjordkommission.utmbackup.storage.LegacyBackupTime;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final BackupStorageGuard storageGuard;
    private final BackupManifestReader manifestReader = new BackupManifestReader();


    public BackupScanner(
            BackupProperties backupProperties,
            StableRepository repo,
            SettingsRepository settings,
            VmInventory vmInventory,
            VmChangeStatusService vmChangeStatusService,
            BackupStorageGuard storageGuard
    ) {
        this.root = backupProperties.root();
        this.repo = repo;
        this.settings = settings;
        this.vmInventory = vmInventory;
        this.vmChangeStatusService = vmChangeStatusService;
        this.storageGuard = storageGuard;
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

            // A backup-only VM takes its name from its newest backup: the manifest name for
            // format 2, or the folder name for format 1, which equals the local id.
            String vmName = displayName(localVm, vmId, backups);

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
     * Change tracking is keyed by the canonical VmId for every provider, so the same
     * local id under two providers keeps two states. A VM without backups is
     * NEVER_BACKED_UP without reading the database.
     */
    private VmChangeStatus changeStatus(
            VmId vmId,
            ManagedVm localVm,
            List<VmBackup> backups
    ) {
        if (localVm == null || localVm.status() != VmStatus.STOPPED) {
            return VmChangeStatus.UNKNOWN;
        }

        return vmChangeStatusService.determine(
                vmId,
                localVm.localVm(),
                !backups.isEmpty()
        );
    }

    /**
     * The display name of a VM: the local VM's name, or for a backup-only VM the name
     * of its newest backup. Format 2 stores the manifest name; format 1 stores the
     * folder name, which equals the local id.
     */
    private static String displayName(ManagedVm localVm, VmId vmId, List<VmBackup> newestFirst) {
        if (localVm != null) {
            return localVm.name();
        }

        return newestFirst.isEmpty() ? vmId.localId() : newestFirst.getFirst().vmName();
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

        BackupManifestResult manifestResult = manifestReader.read(directory);

        if (manifestResult instanceof BackupManifestResult.Parsed(BackupManifest parsed)
                && parsed.formatVersion() == BackupFormat.PROVIDER_AWARE) {
            scanFormatTwoBatch(directory, vmDirectory, parsed, metadata, backupsByVm, batchIssues);
            return;
        }

        Optional<BackupManifest> manifest = legacyManifest(
                directoryName,
                manifestResult,
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
                            persistLegacyStable(id);
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

    /**
     * Stores the Stable state of a legacy _stable directory. This is a migration that
     * happens while scanning, so on a read-only medium it is skipped rather than
     * failing the scan. The Stable flag still applies for this scan.
     */
    private void persistLegacyStable(String id) {
        try {
            storageGuard.assertWritable();
            repo.save(id, true, null);
        } catch (BackupStorageReadOnlyException ignored) {
            // Read-only medium: the scan keeps working and the migration is retried later.
        }
    }

    private String id(String directoryName, String vmFolder) {
        return BackupIdentity.legacyId(directoryName, vmFolder);
    }

    /**
     * Shows the VMs of a format-2 batch. Each manifest entry gives the identity and
     * the container directory. The directory name is never parsed for identity, and
     * containers that the manifest does not list are ignored, so an incomplete copy
     * cannot appear as a backup.
     */
    private void scanFormatTwoBatch(
            Path directory,
            Path vmDirectory,
            BackupManifest manifest,
            Map<String, StableRepository.Meta> metadata,
            Map<VmId, List<VmBackup>> backupsByVm,
            List<BackupBatchIssue> batchIssues
    ) {
        String batchName = directory.getFileName().toString();

        LocalDateTime backupTime = manifest.backupTime().orElseThrow()
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();

        for (BackupManifest.VmEntry entry : manifest.vms()) {
            Path container = vmDirectory.resolve(entry.directory());

            if (Files.isDirectory(container)) {
                addFormatTwoBackup(batchName, entry, container, backupTime, manifest.comment(), metadata, backupsByVm);
            } else {
                batchIssues.add(new BackupBatchIssue(
                        batchName,
                        "VM " + entry.id() + " has no container directory " + entry.directory()
                ));
            }
        }
    }

    private void addFormatTwoBackup(
            String batchName,
            BackupManifest.VmEntry entry,
            Path container,
            LocalDateTime backupTime,
            String comment,
            Map<String, StableRepository.Meta> metadata,
            Map<VmId, List<VmBackup>> backupsByVm
    ) {
        String id = BackupIdentity.formatTwoId(batchName, entry.id());
        StableRepository.Meta storedMetadata = metadata.get(id);

        VmBackup backup = new VmBackup(
                id,
                batchName,
                entry.name(),
                container,
                backupTime,
                comment,
                storedMetadata != null && storedMetadata.stable(),
                storedMetadata != null ? storedMetadata.note() : null
        );

        backupsByVm.computeIfAbsent(entry.id(), ignored -> new ArrayList<>()).add(backup);
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
                        "Format " + manifest.formatVersion() + " backups are not supported by this code path"
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
