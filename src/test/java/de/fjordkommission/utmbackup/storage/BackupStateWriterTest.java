package de.fjordkommission.utmbackup.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BackupStateWriterTest {

    private final BackupStateWriter writer = new BackupStateWriter();
    private final BackupStateReader reader = new BackupStateReader();

    @TempDir
    Path container;

    @Test
    void outputIsDeterministicAndHasNoTimestamp() {
        BackupState state = new BackupState(true, "Before kernel update");

        assertEquals("""
                # UTM Backup Manager: mutable state of this backup, replaced atomically.
                stable=true
                note=Before kernel update
                """, writer.write(state));
        assertEquals(writer.write(state), writer.write(state));
    }

    @Test
    void firstWriteCreatesTheFileAndItReadsBack() {
        writer.writeFile(container, new BackupState(true, ""));

        assertEquals(new BackupStateResult.Parsed(new BackupState(true, "")), reader.read(container));
    }

    @Test
    void noteWithLineBreaksBackslashesAndUtf8RoundTrips() {
        BackupState state = new BackupState(false, "first line\r\nsecond \\ line\nZürich ✓");

        writer.writeFile(container, state);

        assertEquals(new BackupStateResult.Parsed(state), reader.read(container));
    }

    @Test
    void replacementRemovesTheOldContentCompletely() {
        writer.writeFile(container, new BackupState(true, "a long note that is replaced"));
        writer.writeFile(container, new BackupState(false, "short"));

        assertEquals(new BackupStateResult.Parsed(new BackupState(false, "short")), reader.read(container));
        assertEquals(List.of(BackupStateReader.FILE_NAME), entries());
    }

    @Test
    void failedWriteCreatesNoPartialFile() {
        Path missing = container.resolve("no-such-container");
        BackupState state = new BackupState(true, "x");

        assertThrows(UncheckedIOException.class, () -> writer.writeFile(missing, state));

        assertFalse(Files.exists(missing.resolve(BackupStateReader.FILE_NAME)));
    }

    @Test
    void writtenTextIsUtf8() throws Exception {
        writer.writeFile(container, new BackupState(false, "ü"));

        String text = Files.readString(container.resolve(BackupStateReader.FILE_NAME), StandardCharsets.UTF_8);

        assertTrue(text.endsWith("note=ü\n"));
    }

    private List<String> entries() {
        try (Stream<Path> paths = Files.list(container)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
