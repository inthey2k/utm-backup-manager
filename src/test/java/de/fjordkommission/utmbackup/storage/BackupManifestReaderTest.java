package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BackupManifestReaderTest {

    @TempDir
    Path tempDir;

    private final BackupManifestReader reader = new BackupManifestReader();

    @Test
    void batchWithoutManifestIsFormatOneWithEmptyComment() throws IOException {
        Path batch = Files.createDirectories(tempDir.resolve("2026-09-01_1200"));

        BackupManifestResult result = reader.read(batch);

        assertEquals(new BackupManifestResult.Parsed(BackupManifest.legacy("")), result);
    }

    @Test
    void readsFormatTwoManifestFromFile() throws IOException {
        Path batch = Files.createDirectories(tempDir.resolve("2026-10-20_1200"));
        BackupManifest manifest = new BackupManifest(
                BackupFormat.PROVIDER_AWARE,
                Optional.of(OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.ofHours(2))),
                "Before update",
                List.of(new BackupManifest.VmEntry(new VmId("utm", "Linux.utm"), "Linux", "utm__Linux"))
        );
        Files.writeString(batch.resolve(BackupManifestReader.FILE_NAME), new BackupManifestWriter().write(manifest));

        assertEquals(new BackupManifestResult.Parsed(manifest), reader.read(batch));
    }

    @Test
    void invalidUtf8BytesAreReplacedAndLegacyCommentStillParses() throws IOException {
        Path batch = Files.createDirectories(tempDir.resolve("2026-09-01_1200"));
        byte[] latin1Comment = "Comment:\nCafé\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(batch.resolve(BackupManifestReader.FILE_NAME), latin1Comment);

        BackupManifestResult result = reader.read(batch);

        BackupManifest manifest = ((BackupManifestResult.Parsed) result).manifest();
        assertEquals(BackupFormat.LEGACY, manifest.formatVersion());
        assertEquals("Caf�", manifest.comment());
    }

    @Test
    void manifestThatCannotBeReadIsInvalidAndNotLegacy() throws IOException {
        Path batch = Files.createDirectories(tempDir.resolve("2026-09-01_1200"));
        Path file = Files.writeString(batch.resolve(BackupManifestReader.FILE_NAME), "Comment:\nsecret\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(file), "unreadable file requires a non-root user");

        BackupManifestResult result = reader.read(batch);

        assertInstanceOf(BackupManifestResult.Invalid.class, result);
    }
}
