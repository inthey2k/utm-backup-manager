package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.repository.VmStateRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Determines whether a VM has changed and records acknowledged VM states.
 */
@Service
public class VmChangeStatusService {

    private final VmStateRepository repository;

    public VmChangeStatusService(VmStateRepository repository) {
        this.repository = repository;
    }

    public VmChangeStatus determine(
            String technicalName,
            LocalVm vm,
            boolean hasBackup
    ) {
        if (vm == null || vm.backupSources().isEmpty()) {
            return VmChangeStatus.UNKNOWN;
        }

        if (!hasBackup) {
            return VmChangeStatus.NEVER_BACKED_UP;
        }

        Optional<VmStateRepository.State> stored =
                repository.find(technicalName);

        if (stored.isEmpty()) {
            return VmChangeStatus.UNKNOWN;
        }

        try {
            long currentMtime = newestModificationTime(vm);

            return currentMtime == stored.get().acknowledgedMtime()
                    ? VmChangeStatus.UNCHANGED
                    : VmChangeStatus.CHANGED;
        } catch (IOException e) {
            return VmChangeStatus.UNKNOWN;
        }
    }

    /**
     * Records the current VM state after a successful backup.
     */
    public void acknowledgeBackup(String technicalName, LocalVm vm) {
        if (vm == null || vm.backupSources().isEmpty()) {
            throw new IllegalArgumentException(
                    "VM must have at least one backup source"
            );
        }

        try {
            long currentMtime = newestModificationTime(vm);

            repository.save(
                    technicalName,
                    currentMtime,
                    VmStateRepository.Acknowledgement.BACKED_UP
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "VM state could not be determined for: " + technicalName,
                    e
            );
        }
    }

    /**
     * Returns the newest modification time found across all backup sources.
     */
    private long newestModificationTime(LocalVm vm) throws IOException {
        long newest = Long.MIN_VALUE;

        for (Path source : vm.backupSources()) {
            newest = Math.max(
                    newest,
                    newestModificationTime(source)
            );
        }

        return newest;
    }

    /**
     * Includes both the source itself and all descendants. This matches the
     * existing UTM backup-state semantics where directory mtimes also count.
     */
    private long newestModificationTime(Path source) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            return paths
                    .map(this::lastModifiedTime)
                    .flatMap(Optional::stream)
                    .mapToLong(FileTime::toMillis)
                    .map(ms -> ms / 1000)
                    .max()
                    .orElse(Long.MIN_VALUE);
        }
    }

    private Optional<FileTime> lastModifiedTime(Path path) {
        try {
            return Optional.of(Files.getLastModifiedTime(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}