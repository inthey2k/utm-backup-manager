package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.VmIdentityFile;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Gives the rest of the application access to {@code VM-IDENTITIES.json}.
 *
 * <p>The registry is read from the backup root on each call, because the root may be an
 * external drive that is attached or removed at any time. Updates are written only when
 * the content actually changes, so repeated scans do not rewrite the file, and they are
 * skipped on a read-only medium. A file that cannot be used is never overwritten.
 */
@Service
public class VmIdentityService {

    private static final Logger log = LoggerFactory.getLogger(VmIdentityService.class);

    private final Path root;
    private final BackupStorageGuard storageGuard;

    @Autowired
    public VmIdentityService(BackupProperties backupProperties, BackupStorageGuard storageGuard) {
        this.root = backupProperties.root();
        this.storageGuard = storageGuard;
   }

    /**
     * The current registry. An absent file is an empty registry. An unusable file is empty
     * too, so the caller falls back to provider-local ids, and the reason is logged.
     */
    public synchronized Optional<VmIdentityRegistry> current() {
        return switch (VmIdentityFile.read(root)) {
            case VmIdentityFile.ReadResult.Absent() -> Optional.of(VmIdentityRegistry.empty());
            case VmIdentityFile.ReadResult.Loaded(VmIdentityRegistry registry) -> Optional.of(registry);
            case VmIdentityFile.ReadResult.Unusable(String reason) -> {
                log.warn("{} {} and is left unchanged; provider-local ids are used", VmIdentityFile.FILE_NAME, reason);
                yield Optional.empty();
            }
        };
    }

    /**
     * Applies a change and writes the result when it differs from what is stored. Skipped
     * when the registry is unusable, the storage is read-only, or nothing changed.
     */
    public synchronized void update(UnaryOperator<VmIdentityRegistry> change) {
        Optional<VmIdentityRegistry> existing = current();

        if (existing.isEmpty() || !storageGuard.isWritable()) {
            return;
        }

        VmIdentityRegistry updated = change.apply(existing.get());

        if (updated.equals(existing.get())) {
            return;
        }

        try {
            VmIdentityFile.write(root, updated);
        } catch (IOException | UncheckedIOException e) {
            // Identity bookkeeping must never break a scan; the next scan retries.
            log.warn("{} could not be written: {}", VmIdentityFile.FILE_NAME, e.getMessage());
        }
    }

    /** Records observations in one write. */
    public void recordObservations(List<VmIdentityRegistry.Observation> observations) {
        if (observations.isEmpty()) {
            return;
        }

        update(registry -> registry.observeAll(observations));
    }

    /** Make sure to return the canonical ID even for legacy VM IDs **/
    public VmId resolveCanonicalId(VmId vmId) {
        return current()
                .flatMap(registry -> registry.canonicalOf(vmId))
                .orElse(vmId);
    }
}
