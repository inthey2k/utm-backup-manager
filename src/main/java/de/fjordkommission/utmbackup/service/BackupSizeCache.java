package de.fjordkommission.utmbackup.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the size of each backup directory, so that a page refresh does not walk every
 * backup again.
 *
 * <p>A published backup is not changed by the application, so its size is reused. Each entry
 * stores the modification time and file key of its directory. When either differs, the
 * directory was replaced, for example after a backup with the same name was made again, and
 * the size is measured again. Entries of backups that no longer exist are removed by
 * {@link #retainOnly(Collection)}. The cache only reads files, so it cannot affect backup
 * data.
 */
final class BackupSizeCache {

    private final VmSizeService sizes;
    private final Map<Path, Entry> entries = new ConcurrentHashMap<>();

    BackupSizeCache(VmSizeService sizes) {
        this.sizes = sizes;
    }

    /**
     * Returns the size of a backup directory, or empty when it cannot be read.
     */
    OptionalLong sizeOf(Path directory) {
        Path key = directory.toAbsolutePath().normalize();

        BasicFileAttributes attributes;

        try {
            attributes = Files.readAttributes(key, BasicFileAttributes.class);
        } catch (IOException | SecurityException e) {
            entries.remove(key);
            return OptionalLong.empty();
        }

        Entry cached = entries.get(key);

        if (cached != null && cached.matches(attributes)) {
            return OptionalLong.of(cached.bytes());
        }

        OptionalLong measured = sizes.sizeOfTreeIfReadable(key);

        if (measured.isPresent()) {
            entries.put(key, new Entry(
                    measured.getAsLong(),
                    attributes.lastModifiedTime(),
                    attributes.fileKey()
            ));
        } else {
            entries.remove(key);
        }

        return measured;
    }

    /**
     * Forgets every entry whose backup is not among the given directories.
     */
    void retainOnly(Collection<Path> directories) {
        Set<Path> keep = Set.copyOf(
                directories.stream()
                        .map(directory -> directory.toAbsolutePath().normalize())
                        .toList()
        );

        entries.keySet().removeIf(key -> !keep.contains(key));
    }

    /** Number of remembered directories. Used by tests to check that entries are pruned. */
    int size() {
        return entries.size();
    }

    private record Entry(long bytes, FileTime modified, Object fileKey) {

        boolean matches(BasicFileAttributes attributes) {
            return modified.equals(attributes.lastModifiedTime())
                    && (fileKey == null
                    ? attributes.fileKey() == null
                    : fileKey.equals(attributes.fileKey()));
        }
    }
}
