package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The metadata of one backup batch, as read from or written to its
 * {@code BACKUP-INFO.txt}.
 *
 * Format 1 has no provider-aware VM entries and no explicit time: its time is the
 * file-system time, so {@link #backupTime()} is empty. Format 2 always carries an
 * offset-aware time and one entry per VM.
 *
 * @param formatVersion batch format, see {@link BackupFormat}
 * @param backupTime    offset-aware time (format 2 only)
 * @param comment       free-text comment, possibly empty
 * @param vms           VM entries (format 2 only)
 */
public record BackupManifest(
        int formatVersion,
        Optional<OffsetDateTime> backupTime,
        String comment,
        List<VmEntry> vms
) {

    public BackupManifest {
        Objects.requireNonNull(backupTime, "backupTime");
        Objects.requireNonNull(comment, "comment");
        vms = List.copyOf(vms);

        if (formatVersion == BackupFormat.LEGACY) {
            if (backupTime.isPresent() || !vms.isEmpty()) {
                throw new IllegalArgumentException("Format 1 manifests carry no time or VM entries");
            }
        } else if (formatVersion == BackupFormat.PROVIDER_AWARE) {
            if (backupTime.isEmpty()) {
                throw new IllegalArgumentException("Format 2 manifests require an offset-aware time");
            }
            requireUniqueVms(vms);
        } else {
            throw new IllegalArgumentException("Unsupported batch format: " + formatVersion);
        }
    }

    /**
     * Returns a format-1 manifest, which has only a comment.
     */
    public static BackupManifest legacy(String comment) {
        return new BackupManifest(BackupFormat.LEGACY, Optional.empty(), comment, List.of());
    }

    private static void requireUniqueVms(List<VmEntry> vms) {
        Set<VmId> ids = new HashSet<>();
        Set<String> directories = new HashSet<>();

        for (VmEntry vm : vms) {
            if (!ids.add(vm.id())) {
                throw new IllegalArgumentException("Duplicate VM id in batch: " + vm.id());
            }

            // Compare directories the way the file system may: Unicode-normalized and case-insensitive.
            String key = Normalizer.normalize(vm.directory(), Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);

            if (!directories.add(key)) {
                throw new IllegalArgumentException("Duplicate container directory in batch: " + vm.directory());
            }
        }
    }

    /**
     * One VM of a format-2 batch.
     *
     * @param id        canonical identity, {@code providerId:localId}
     * @param name      display name; defaults to the local id when absent in the file
     * @param directory name of the VM's container directory inside {@code VMs/}
     */
    public record VmEntry(VmId id, String name, String directory) {

        public VmEntry {
            Objects.requireNonNull(id, "id");

            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("VM name must not be blank: " + id);
            }

            requireSinglePathElement(directory);
        }

        /**
         * A container directory must be one plain name inside {@code VMs/}, so a
         * manifest can never point outside its batch.
         */
        static void requireSinglePathElement(String directory) {
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("Directory must not be blank");
            }

            if (directory.contains("/") || directory.contains("\\")) {
                throw new IllegalArgumentException("Directory must not contain path separators: " + directory);
            }

            if (directory.contains("..") || directory.equals(".")) {
                throw new IllegalArgumentException("Directory must not reference parent or current directories: " + directory);
            }

            if (directory.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Directory must not contain control characters");
            }
        }
    }
}
