package de.fjordkommission.utmbackup.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BackupStorageMetadataWriterTest {

    private static final OffsetDateTime CREATED =
            OffsetDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneOffset.ofHours(2));

    @TempDir
    Path root;

    private final BackupStorageMetadataWriter writer = new BackupStorageMetadataWriter();
    private final BackupStorageMetadataReader reader = new BackupStorageMetadataReader();

    private static BackupRootMetadata metadata(int formatVersion, String lastWrittenBy) {
        return new BackupRootMetadata(formatVersion, "UTM Backup Manager", CREATED, "1.5.0", lastWrittenBy);
    }

    @Test
    void writesTheDocumentedPropertiesInFixedOrder() throws IOException {
        writer.write(root, metadata(2, "1.5.0"));

        assertEquals(String.join("\n",
                "format.version=2",
                "application.name=UTM Backup Manager",
                "created.at=2026-10-21T12:00:00+02:00",
                "created.by.version=1.5.0",
                "last.written.by.version=1.5.0",
                ""), read());
    }

    @Test
    void identicalMetadataProducesIdenticalBytes() throws IOException {
        writer.write(root, metadata(2, "1.5.0"));
        byte[] first = Files.readAllBytes(root.resolve(BackupStorageMetadataReader.FILE_NAME));

        writer.write(root, metadata(2, "1.5.0"));
        byte[] second = Files.readAllBytes(root.resolve(BackupStorageMetadataReader.FILE_NAME));

        assertArrayEquals(first, second);
    }

    @Test
    void writtenFileReadsBackToTheSameMetadata() {
        BackupRootMetadata written = metadata(2, "1.5.0");

        writer.write(root, written);

        assertEquals(new BackupStorageMetadataResult.Present(written), reader.read(root));
    }

    @Test
    void replacementUpdatesTheFileAndLeavesNoTemporaryFiles() throws IOException {
        writer.write(root, metadata(2, "1.5.0"));

        writer.write(root, metadata(2, "1.6.0"));

        assertTrue(read().contains("last.written.by.version=1.6.0"));
        assertEquals(List.of(BackupStorageMetadataReader.FILE_NAME), entries());
    }

    @Test
    void writerNeverReplacesNewerStorageFormat() throws IOException {
        Path file = root.resolve(BackupStorageMetadataReader.FILE_NAME);
        Files.writeString(file, "format.version=3\ncreated.at=2026-10-21T12:00:00+02:00\n");
        String before = read();

        BackupRootMetadata valid = metadata(2, "1.5.0");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> writer.write(root, valid)
        );

        assertTrue(exception.getMessage().contains("newer storage format 3"));
        assertEquals(before, read());
        assertEquals(List.of(BackupStorageMetadataReader.FILE_NAME), entries());
    }

    @Test
    void writerNeverRepairsInvalidMetadata() throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), "garbage without separator\n");
        String before = read();

        BackupRootMetadata valid = metadata(2, "1.5.0");

        assertThrows(IllegalStateException.class, () -> writer.write(root, valid));

        assertEquals(before, read());
        assertEquals(List.of(BackupStorageMetadataReader.FILE_NAME), entries());
    }

    @Test
    void writerRefusesToWriteAFormatItDoesNotSupport() throws IOException {
        BackupRootMetadata newer = metadata(3, "1.5.0");

        assertThrows(IllegalArgumentException.class, () -> writer.write(root, newer));

        assertEquals(List.of(), entries());
    }

    @Test
    void valuesThatWouldNotReadBackUnchangedAreRefused() throws IOException {
        BackupRootMetadata multiLine = new BackupRootMetadata(2, "UTM\nBackup", CREATED, "1.5.0", "1.5.0");

        assertThrows(IllegalArgumentException.class, () -> writer.write(root, multiLine));

        assertEquals(List.of(), entries());
    }

    @Test
    void nonAsciiValuesAreWrittenAsUtf8() {
        BackupRootMetadata umlaut = new BackupRootMetadata(2, "Übersicht Backup", CREATED, "1.5.0", "1.5.0");

        writer.write(root, umlaut);

        assertEquals(new BackupStorageMetadataResult.Present(umlaut), reader.read(root));
    }

    private String read() throws IOException {
        return Files.readString(root.resolve(BackupStorageMetadataReader.FILE_NAME), StandardCharsets.UTF_8);
    }

    private List<String> entries() throws IOException {
        try (Stream<Path> paths = Files.list(root)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
