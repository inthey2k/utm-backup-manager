package de.fjordkommission.utmbackup.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class BackupStorageMetadataReaderTest {

    private static final String VALID = String.join("\n",
            "format.version=2",
            "application.name=UTM Backup Manager",
            "created.at=2026-10-21T12:00:00+02:00",
            "created.by.version=1.5.0",
            "last.written.by.version=1.5.0",
            "");

    @TempDir
    Path root;

    private final BackupStorageMetadataReader reader = new BackupStorageMetadataReader();

    @Test
    void missingFileIsAbsentAndNotInvalid() {
        assertInstanceOf(BackupStorageMetadataResult.Absent.class, reader.read(root));
    }

    @Test
    void validMetadataIsReadWithOffsetTime() throws IOException {
        write(VALID);

        BackupRootMetadata metadata = present(reader.read(root));

        assertEquals(2, metadata.formatVersion());
        assertEquals("UTM Backup Manager", metadata.applicationName());
        assertEquals(OffsetDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneOffset.ofHours(2)), metadata.createdAt());
        assertEquals("1.5.0", metadata.createdByVersion());
        assertEquals("1.5.0", metadata.lastWrittenByVersion());
    }

    @Test
    void newerFormatVersionIsReportedExplicitly() throws IOException {
        write(VALID.replace("format.version=2", "format.version=3"));

        assertEquals(new BackupStorageMetadataResult.UnsupportedVersion(3), reader.read(root));
    }

    @Test
    void newerFormatIsReportedEvenWhenOtherPropertiesAreMissing() throws IOException {
        write("format.version=7\n");

        assertEquals(new BackupStorageMetadataResult.UnsupportedVersion(7), reader.read(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"format.version=two", "format.version=2.0", "format.version=0", "format.version=-1", "format.version="})
    void malformedFormatVersionIsInvalid(String line) throws IOException {
        write(VALID.replace("format.version=2", line));

        assertInstanceOf(BackupStorageMetadataResult.Invalid.class, reader.read(root));
    }

    @Test
    void missingFormatVersionIsInvalid() throws IOException {
        write(VALID.replace("format.version=2\n", ""));

        assertInvalid(reader.read(root), "format.version is missing");
    }

    @Test
    void missingCreatedAtIsInvalid() throws IOException {
        write(VALID.replace("created.at=2026-10-21T12:00:00+02:00\n", ""));

        assertInvalid(reader.read(root), "created.at is missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-21T12:00:00", "21.10.2026 12:00", "2026-10-21", "not a date"})
    void createdAtWithoutValidOffsetIsInvalid(String createdAt) throws IOException {
        write(VALID.replace("2026-10-21T12:00:00+02:00", createdAt));

        assertInstanceOf(BackupStorageMetadataResult.Invalid.class, reader.read(root));
    }

    @Test
    void unknownPropertiesAreIgnored() throws IOException {
        write(VALID + "future.setting=anything\nanother.one=42\n");

        assertEquals(2, present(reader.read(root)).formatVersion());
    }

    @Test
    void byteOrderMarkIsIgnored() throws IOException {
        write("\uFEFF" + VALID);

        assertEquals(2, present(reader.read(root)).formatVersion());
    }

    @Test
    void crlfLineEndingsAreAccepted() throws IOException {
        write(VALID.replace("\n", "\r\n"));

        assertEquals("1.5.0", present(reader.read(root)).lastWrittenByVersion());
    }

    @Test
    void repeatedKnownPropertyIsInvalid() throws IOException {
        write(VALID + "format.version=2\n");

        assertInvalid(reader.read(root), "appears more than once");
    }

    @Test
    void malformedLineIsInvalid() throws IOException {
        write(VALID + "this line has no separator\n");

        assertInvalid(reader.read(root), "Malformed line");
    }

    @Test
    void invalidUtf8IsRejectedBecauseTheMarkerIsMachineRead() throws IOException {
        byte[] bytes = (VALID + "application.name=Caf").getBytes(StandardCharsets.UTF_8);
        byte[] damaged = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, damaged, 0, bytes.length);
        damaged[bytes.length] = (byte) 0xFF;
        Files.write(root.resolve(BackupStorageMetadataReader.FILE_NAME), damaged);

        assertInvalid(reader.read(root), "not valid UTF-8");
    }

    @Test
    void directoryWithTheMarkerNameIsInvalidNotAbsent() throws IOException {
        Files.createDirectories(root.resolve(BackupStorageMetadataReader.FILE_NAME));

        assertInvalid(reader.read(root), "not a regular file");
    }

    private void write(String text) throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), text, StandardCharsets.UTF_8);
    }

    private static BackupRootMetadata present(BackupStorageMetadataResult result) {
        assertInstanceOf(BackupStorageMetadataResult.Present.class, result, String.valueOf(result));

        return ((BackupStorageMetadataResult.Present) result).metadata();
    }

    private static void assertInvalid(BackupStorageMetadataResult result, String reasonFragment) {
        assertInstanceOf(BackupStorageMetadataResult.Invalid.class, result, String.valueOf(result));

        String reason = ((BackupStorageMetadataResult.Invalid) result).reason();
        assertTrue(reason.contains(reasonFragment), reason);
    }
}
