package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.repository.VmStateRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Legacy fix
 * Imports the states of the VMs, which used to be stored in a text file,
 * into our repository database.
 */
@Service
public class VmStateImporter {
    private final Path stateFile;
    private final VmStateRepository repository;

    public VmStateImporter(BackupProperties backupProperties, VmStateRepository repository) {
        this.stateFile = backupProperties.legacyStateFile();
        this.repository = repository;
    }

    @PostConstruct
    void importLegacyState() {
        if (!Files.isRegularFile(stateFile)) return;

        try {
            for (String line : Files.readAllLines(stateFile)) importLine(line);
        } catch (IOException e) {
            throw new IllegalStateException("Legacy VM state could not be imported: " + stateFile, e);
        }
    }

    private void importLine(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length < 3 || repository.find(fields[0]).isPresent()) return;

        try {
            long mtime = Long.parseLong(fields[1]);
            VmStateRepository.Acknowledgement acknowledgement = switch (fields[2].trim().toLowerCase()) {
                case "backed-up" -> VmStateRepository.Acknowledgement.BACKED_UP;
                case "skipped" -> VmStateRepository.Acknowledgement.SKIPPED;
                default -> null;
            };
            if (acknowledgement != null) repository.save(fields[0], mtime, acknowledgement);
        } catch (NumberFormatException ignored) {
            // Ignore malformed legacy rows without blocking application startup.
        }
    }
}
