package de.fjordkommission.utmbackup.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackupStateReaderTest {

    private final BackupStateReader reader = new BackupStateReader();

    @TempDir
    Path container;

    @Test
    void missingFileGivesAbsentWhichMeansDefaults() {
        assertInstanceOf(BackupStateResult.Absent.class, reader.read(container));
    }

    @Test
    void validStableTrueAndNote() {
        assertEquals(parsed(true, "Before kernel update"),
                reader.parse("stable=true\nnote=Before kernel update\n"));
    }

    @Test
    void validStableFalseWithoutNote() {
        assertEquals(parsed(false, ""), reader.parse("stable=false\n"));
    }

    @Test
    void noteIsOptionalAndDefaultsToEmpty() {
        assertEquals(parsed(true, ""), reader.parse("stable=true\n"));
    }

    @Test
    void escapedNoteRestoresBackslashAndLineBreaks() {
        assertEquals(parsed(false, "a\\b\nc\rd"),
                reader.parse("stable=false\nnote=a\\\\b\\nc\\rd\n"));
    }

    @Test
    void utf8NoteIsReadAsUtf8() throws IOException {
        Files.writeString(container.resolve(BackupStateReader.FILE_NAME),
                "stable=false\nnote=Zürich ✓ 日本\n", StandardCharsets.UTF_8);

        assertEquals(parsed(false, "Zürich ✓ 日本"), reader.read(container));
    }

    @Test
    void crlfLineEndingsAreAccepted() {
        assertEquals(parsed(true, "note"), reader.parse("stable=true\r\nnote=note\r\n"));
    }

    @Test
    void byteOrderMarkIsIgnored() {
        assertEquals(parsed(true, ""), reader.parse("﻿stable=true\n"));
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        assertEquals(parsed(true, "x"), reader.parse("# comment\n\nstable=true\n   \nnote=x\n"));
    }

    @Test
    void unknownPropertiesAreIgnored() {
        assertEquals(parsed(false, ""), reader.parse("future.flag=1\nstable=false\nother=x\n"));
    }

    @Test
    void malformedStableValueIsRejected() {
        assertInvalid("stable=yes\n");
        assertInvalid("stable=TRUE\n");
        assertInvalid("stable=\n");
    }

    @Test
    void missingStableIsRejectedEvenWhenNoteIsPresent() {
        assertInvalid("note=only a note\n");
    }

    @Test
    void duplicateKnownKeyIsRejected() {
        assertInvalid("stable=true\nstable=false\n");
        assertInvalid("stable=true\nnote=a\nnote=b\n");
    }

    @Test
    void lineWithoutSeparatorIsRejected() {
        assertInvalid("stable=true\njunk\n");
    }

    @Test
    void unknownEscapeIsRejected() {
        assertInvalid("stable=true\nnote=a\\qb\n");
    }

    @Test
    void incompleteEscapeAtEndIsRejected() {
        assertInvalid("stable=true\nnote=ends with\\\n");
    }

    @Test
    void invalidUtf8IsInvalidNotReplaced() throws IOException {
        Files.write(container.resolve(BackupStateReader.FILE_NAME),
                new byte[]{'s', 't', 'a', 'b', 'l', 'e', '=', 't', 'r', 'u', 'e', '\n', 'n', 'o', 't', 'e', '=', (byte) 0xFF});

        assertInstanceOf(BackupStateResult.Invalid.class, reader.read(container));
    }

    @Test
    void stateThatIsNotARegularFileIsInvalid() throws IOException {
        Files.createDirectory(container.resolve(BackupStateReader.FILE_NAME));

        assertInstanceOf(BackupStateResult.Invalid.class, reader.read(container));
    }

    private void assertInvalid(String text) {
        assertInstanceOf(BackupStateResult.Invalid.class, reader.parse(text), text);
    }

    private static BackupStateResult parsed(boolean stable, String note) {
        return new BackupStateResult.Parsed(new BackupState(stable, note));
    }
}
