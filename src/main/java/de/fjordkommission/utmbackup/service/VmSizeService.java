package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class VmSizeService {

    /**
     * Returns the total size of all backup sources of a VM.
     */
    public long sizeOf(LocalVm vm) {
        if (vm == null) {
            throw new IllegalArgumentException("VM must not be null");
        }

        try {
            long totalBytes = 0;

            for (Path source : vm.backupSources()) {
                totalBytes = Math.addExact(
                        totalBytes,
                        sizeOf(source)
                );
            }

            return totalBytes;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not determine size of VM: " + vm.id(),
                    e
            );
        }
    }

    private long sizeOf(Path source) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException(
                    "Backup source does not exist: " + source
            );
        }

        if (Files.isRegularFile(source)) {
            return Files.size(source);
        }

        if (!Files.isDirectory(source)) {
            return 0;
        }

        try (var paths = Files.walk(source)) {
            return paths
                    .filter(Files::isRegularFile)
                    .mapToLong(this::sizeOfFile)
                    .reduce(0L, Math::addExact);
        }
    }

    private long sizeOfFile(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}