package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.repository.VmStateRepository;
import org.springframework.context.annotation.DependsOn;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Imports the states of the VMs, which used to be stored in a text file, into our
 * repository database. Its keys are UTM folder names and are stored under their
 * canonical {@code utm:} ids. It runs after the key migration, so it never writes a
 * legacy key.
 */
@Service
@DependsOn("vmStateKeyMigration")
public class VmStateImporter {
    private final Path stateFile;
    private final VmStateRepository repository;

    public VmStateImporter(BackupProperties backupProperties, VmStateRepository repository) {
        this.stateFile = backupProperties.legacyStateFile();
        this.repository = repository;
    }

    @PostConstruct
    public void importLegacyState() {
        if (!Files.isRegularFile(stateFile)) return;

        try {
            for (String line : Files.readAllLines(stateFile)) importLine(line);
        } catch (IOException e) {
            throw new IllegalStateException("Legacy VM state could not be imported: " + stateFile, e);
        }
    }

    private void importLine(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length < 3 || fields[0].isBlank()) return;

        VmId vmId = LegacyBackupLayout.vmIdForFolder(fields[0]);

        if (repository.find(vmId).isPresent()) return;

        try {
            long mtime = Long.parseLong(fields[1]);
            VmStateRepository.Acknowledgement acknowledgement = switch (fields[2].trim().toLowerCase()) {
                case "backed-up" -> VmStateRepository.Acknowledgement.BACKED_UP;
                case "skipped" -> VmStateRepository.Acknowledgement.SKIPPED;
                default -> null;
            };
            if (acknowledgement != null) repository.save(vmId, mtime, acknowledgement);
        } catch (NumberFormatException ignored) {
            // Ignore malformed legacy rows without blocking application startup.
        }
    }
}
