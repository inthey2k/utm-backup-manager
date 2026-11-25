package de.fjordkommission.utmbackup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupSizeCacheTest {

    @TempDir
    Path tempDirectory;

    private final BackupSizeCache cache = new BackupSizeCache(new VmSizeService());

    @Test
    void sizeIsTheTotalOfAllFilesBelowTheBackupDirectory() throws IOException {
        Path backup = backupDirectory("backup", "a", 100);
        Files.createDirectories(backup.resolve("Data"));
        Files.write(backup.resolve("Data/disk.img"), new byte[50]);

        assertEquals(OptionalLong.of(150), cache.sizeOf(backup));
    }

    @Test
    void unchangedDirectoryReusesTheRememberedSize() throws IOException {
        Path backup = backupDirectory("backup", "a", 100);
        assertEquals(OptionalLong.of(100), cache.sizeOf(backup));

        // Changing a file inside does not change the directory's entries, so the published
        // backup keeps the size measured when it was first seen.
        Files.write(backup.resolve("a"), new byte[300]);

        assertEquals(OptionalLong.of(100), cache.sizeOf(backup));
    }

    @Test
    void changedDirectoryIsMeasuredAgain() throws IOException {
        Path backup = backupDirectory("backup", "a", 100);
        assertEquals(OptionalLong.of(100), cache.sizeOf(backup));

        // A new entry changes the directory's modification time, which invalidates the entry.
        Files.write(backup.resolve("b"), new byte[10]);

        assertEquals(OptionalLong.of(110), cache.sizeOf(backup));
    }

    @Test
    void missingBackupIsReportedAsUnknownAndForgotten() throws IOException {
        Path backup = backupDirectory("backup", "a", 100);
        assertTrue(cache.sizeOf(backup).isPresent());

        cache.retainOnly(List.of());

        assertEquals(0, cache.size());
        Files.delete(backup.resolve("a"));
        Files.delete(backup);
        assertFalse(cache.sizeOf(backup).isPresent());
    }

    @Test
    void entriesOfBackupsThatAreStillListedAreKept() throws IOException {
        Path kept = backupDirectory("kept", "a", 1);
        Path removed = backupDirectory("removed", "a", 1);
        cache.sizeOf(kept);
        cache.sizeOf(removed);

        cache.retainOnly(List.of(kept));

        assertEquals(1, cache.size());
        assertTrue(cache.sizeOf(kept).isPresent());
    }

    /** Creates a directory with one file and a fixed past modification time, so later changes are visible. */
    private Path backupDirectory(String name, String fileName, int bytes) throws IOException {
        Path directory = tempDirectory.resolve(name);
        Files.createDirectories(directory);
        Files.write(directory.resolve(fileName), new byte[bytes]);
        Files.setLastModifiedTime(directory, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        return directory;
    }
}
