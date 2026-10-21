package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.config.BackupProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class BackupStorageGuardTest {

    @TempDir
    Path root;

    private BackupStorageGuard guard() {
        return new BackupStorageGuard(new BackupProperties(root, null, null));
    }

    private void marker(String formatVersion) throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), String.join("\n",
                "format.version=" + formatVersion,
                "created.at=2026-10-21T12:00:00+02:00",
                ""));
    }

    @Test
    void writeIsAllowedInLegacyMedium() {
        assertDoesNotThrow(guard()::assertWritable);
        assertEquals(BackupStorageCompatibility.LEGACY, guard().compatibility());
    }

    @Test
    void writeIsAllowedInSupportedMedium() throws IOException {
        marker("2");

        assertDoesNotThrow(guard()::assertWritable);
        assertTrue(guard().isWritable());
    }

    @Test
    void writeIsRejectedForNewerFormatWithTheDomainReason() throws IOException {
        marker("3");

        BackupStorageReadOnlyException exception =
                assertThrows(BackupStorageReadOnlyException.class, guard()::assertWritable);

        assertEquals(BackupStorageCompatibility.READ_ONLY_NEWER_FORMAT, exception.status().compatibility());
        assertTrue(exception.getMessage().startsWith("Backup storage is read-only: "), exception.getMessage());
        assertTrue(exception.getMessage().contains("storage format 3"), exception.getMessage());
    }

    @Test
    void writeIsRejectedForInvalidMetadata() throws IOException {
        marker("two");

        assertThrows(BackupStorageReadOnlyException.class, guard()::assertWritable);
        assertFalse(guard().isWritable());
    }

    @Test
    void rootMetadataCanBeWrittenInLegacyMediumAndThenReadAsSupported() {
        BackupRootMetadata metadata = new BackupRootMetadata(
                2,
                "UTM Backup Manager",
                OffsetDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneOffset.ofHours(2)),
                "1.5.0",
                "1.5.0"
        );

        guard().writeRootMetadata(metadata);

        assertEquals(BackupStorageCompatibility.SUPPORTED, guard().compatibility());
    }

    @Test
    void rootMetadataIsNotWrittenWhenTheMediumIsReadOnly() throws IOException {
        marker("3");
        String before = Files.readString(root.resolve(BackupStorageMetadataReader.FILE_NAME));
        BackupRootMetadata metadata = new BackupRootMetadata(
                2, "UTM Backup Manager",
                OffsetDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneOffset.ofHours(2)),
                "1.5.0", "1.5.0");

        BackupStorageGuard guard = guard();

        assertThrows(BackupStorageReadOnlyException.class, () -> guard.writeRootMetadata(metadata));

        assertEquals(before, Files.readString(root.resolve(BackupStorageMetadataReader.FILE_NAME)));
    }

    @Test
    void missingBackupDirectoryIsReportedUnavailable() {
        BackupStorageGuard guard = new BackupStorageGuard(
                new BackupProperties(root.resolve("missing"), null, null));

        assertEquals(BackupStorageCompatibility.UNAVAILABLE, guard.compatibility());
        assertThrows(BackupStorageReadOnlyException.class, guard::assertWritable);
    }
}
