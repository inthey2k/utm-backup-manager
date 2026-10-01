package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.*;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.repository.SettingsRepository;
import de.fjordkommission.utmbackup.repository.StableRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Browses the folder on the backup drive for VM backup files in order
 * to display them in the overview page.
 */
@Service
public class BackupScanner {

    private static final Pattern BACKUP_DIRECTORY =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:_(\\d{4}))?.*$");

    private final Path root;
    private final StableRepository repo;
    private final SettingsRepository settings;
    private final VmProviderRegistry vmProviderRegistry;
    private final VmChangeStatusService vmChangeStatusService;


    public BackupScanner(
            BackupProperties backupProperties,
            StableRepository repo,
            SettingsRepository settings,
            VmProviderRegistry vmProviderRegistry,
            VmChangeStatusService vmChangeStatusService
    ) {
        this.root = backupProperties.root();
        this.repo = repo;
        this.settings = settings;
        this.vmProviderRegistry = vmProviderRegistry;
        this.vmChangeStatusService = vmChangeStatusService;
    }
    public Path root() {
        return root;
    }

    /**
     * Scans local VMs and existing backups and groups them by VM.
     *
     * Local VMs without backups and backup-only VMs are both included so
     * neither newly created VMs nor historical backups disappear from the UI.
     */
    public BackupOverview scan() {
        int retentionLimit = settings.getRetentionLimit();

        Map<String, StableRepository.Meta> metadata = repo.findAll();
        Map<String, List<VmBackup>> backupsByVm =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        if (!Files.isDirectory(root)) {
            throw new BackupRootUnavailableException(root);
        }

        scanBackupDirectories(backupsByVm, metadata);

        VmProvider vmProvider = vmProviderRegistry.current();
        List<LocalVm> localVms = vmProvider.findAll();
        Map<String, VmStatus> statuses = vmProvider.findStatuses();

        Map<String, LocalVm> localVmsById =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        localVms.forEach(vm ->
                localVmsById.put(vm.id(), vm)
        );

        // Include backup-only VMs so historical backups remain manageable.
        Set<String> vmIds =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        vmIds.addAll(localVmsById.keySet());
        vmIds.addAll(backupsByVm.keySet());

        Set<String> retentionExceeded = new HashSet<>();
        Set<String> retentionCandidatesForDeletion = new HashSet<>();
        List<VmOverview> vms = new ArrayList<>();

        // Build combined list of local VMs and backup-only VMs
        for (String vmId : vmIds) {

            LocalVm localVm = localVmsById.get(vmId);

            String vmName = localVm != null
                    ? localVm.name()
                    : vmId;

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
                    ? statuses.getOrDefault(vmName, VmStatus.UNKNOWN)
                    : VmStatus.UNKNOWN;

            VmChangeStatus changeStatus = status == VmStatus.STOPPED
                    ? vmChangeStatusService.determine(
                    vmId,
                    localVm,
                    !backups.isEmpty()
            )
                    : VmChangeStatus.UNKNOWN;
            vms.add(
                    new VmOverview(
                            vmId,
                            vmName,
                            localVm,
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
                retentionLimit
        );
    }

    /**
     * Finds a backup by its generated ID.
     *
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
     * Reads all dated backup directories and groups their VM packages by name.
     */
    private void scanBackupDirectories(
            Map<String, List<VmBackup>> backupsByVm,
            Map<String, StableRepository.Meta> metadata
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
                                    metadata
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
     *
     * Each direct subdirectory below "VMs" represents one VM and is identified
     * by its provider-defined technical ID.
     */
    private void scanBackupDirectory(
            Path directory,
            Map<String, List<VmBackup>> backupsByVm,
            Map<String, StableRepository.Meta> metadata
    ) {
        Path vmDirectory = directory.resolve("VMs");

        if (!Files.isDirectory(vmDirectory)) {
            return;
        }

        String directoryName = directory.getFileName().toString();

        boolean legacyStable = directoryName
                .toLowerCase(Locale.ROOT)
                .contains("_stable");

        String comment = comment(
                directory.resolve("BACKUP-INFO.txt")
        );

        LocalDateTime backupTime = time(directory);

        try (Stream<Path> vmPaths = Files.list(vmDirectory)) {
            vmPaths
                    .filter(Files::isDirectory)
                    .forEach(path -> {
                        String vmId = path.getFileName().toString();
                        String id = id(directoryName, vmId);

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
                                vmId,
                                path,
                                backupTime,
                                comment,
                                stable,
                                note
                        );

                        backupsByVm
                                .computeIfAbsent(
                                        vmId,
                                        ignored -> new ArrayList<>()
                                )
                                .add(backup);
                    });

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String id(String directoryName, String vmId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (directoryName + "\n" + vmId)
                                .getBytes(StandardCharsets.UTF_8)
                );
    }
    /**
     * Determines the backup time from BACKUP-INFO.txt or, as a fallback,
     * from the backup directory name.
     */
    private LocalDateTime time(Path directory) {
        Path info = directory.resolve("BACKUP-INFO.txt");

        if (Files.isRegularFile(info)) {
            try {
                return LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(info).toInstant(),
                        ZoneId.systemDefault()
                );
            } catch (IOException ignored) {
                // Fall back to the timestamp encoded in the directory name.
            }
        }

        Matcher matcher =
                BACKUP_DIRECTORY.matcher(
                        directory.getFileName().toString()
                );

        if (matcher.matches()) {
            LocalDate date = LocalDate.parse(matcher.group(1));

            if (matcher.group(2) != null) {
                LocalTime time = LocalTime.parse(
                        matcher.group(2),
                        DateTimeFormatter.ofPattern("HHmm")
                );

                return LocalDateTime.of(date, time);
            }

            return date.atStartOfDay();
        }

        return LocalDateTime.MIN;
    }

    /**
     * Reads the backup comment from BACKUP-INFO.txt.
     */
    private String comment(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }

        try {
            List<String> lines = Files.readAllLines(path);

            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("Comment:")
                        && i + 1 < lines.size()) {
                    return lines.get(i + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // A missing comment must not make an otherwise valid backup unusable.
        }

        return "";
    }


}