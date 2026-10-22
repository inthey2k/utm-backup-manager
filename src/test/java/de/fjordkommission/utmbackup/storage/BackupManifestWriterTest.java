package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BackupManifestWriterTest {

    private final BackupManifestWriter writer = new BackupManifestWriter();
    private final BackupManifestParser parser = new BackupManifestParser();

    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.ofHours(2));

    private static BackupManifest manifest(String comment, BackupManifest.VmEntry... vms) {
        return new BackupManifest(BackupFormat.PROVIDER_AWARE, Optional.of(TIME), comment, List.of(vms));
    }

    private static BackupManifest.VmEntry vm(String provider, String localId, String name, String directory) {
        return new BackupManifest.VmEntry(new VmId(provider, localId), name, directory);
    }

    @Test
    void writtenManifestReadsBackToTheSameEntriesTimeAndComment() {
        BackupManifest original = manifest(
                "Before kernel update\n\n  indented\nlast line",
                vm("utm", "ArchLinux 02.27.utm", "ArchLinux 02.27", "utm__ArchLinux-02.27"),
                vm("qemu", "ArchLinux", "Arch Linux", "qemu__ArchLinux")
        );

        BackupManifestResult result = parser.parse(writer.write(original));

        assertEquals(new BackupManifestResult.Parsed(original), result);
    }

    @Test
    void writtenTextStartsWithFormatVersionAndUsesOffsetTime() {
        String text = writer.write(manifest("comment", vm("utm", "Linux.utm", "Linux", "utm__Linux")));

        assertTrue(text.contains("\nFormat-Version: 2\n"), text);
        assertTrue(text.contains("\nTime: 2026-10-20T12:00:00+02:00\n"), text);
        assertTrue(text.contains("\nVM:\nProvider: utm\nVM-ID: Linux.utm\nName: Linux\nDirectory: utm__Linux\n"), text);
        assertTrue(text.endsWith("\nComment:\ncomment\n"), text);
    }

    @Test
    void nonAsciiNamesAreWrittenAsUtf8AndReadBack() {
        BackupManifest original = manifest("Ärger über Übergröße",
                vm("utm", "Büro-VM", "Büro – Ärger", "utm__Buero"));

        byte[] bytes = writer.write(original).getBytes(StandardCharsets.UTF_8);
        String decoded = new String(bytes, StandardCharsets.UTF_8);

        assertEquals(new BackupManifestResult.Parsed(original), parser.parse(decoded));
    }

    @Test
    void emptyCommentIsWrittenAndReadBackAsEmpty() {
        BackupManifest original = manifest("", vm("utm", "Linux.utm", "Linux", "utm__Linux"));

        BackupManifest readBack = ((BackupManifestResult.Parsed) parser.parse(writer.write(original))).manifest();

        assertEquals("", readBack.comment());
    }

    @Test
    void formatOneManifestIsNotWritten() {
        BackupManifest legacy = BackupManifest.legacy("old");

        assertThrows(IllegalArgumentException.class, () -> writer.write(legacy));
    }

    @Test
    void nameWithLineBreakIsRefusedInsteadOfCorruptingTheManifest() {
        BackupManifest unsafe = manifest("c", vm("utm", "Linux.utm", "Line\nBreak", "utm__Linux"));

        assertThrows(IllegalArgumentException.class, () -> writer.write(unsafe));
    }

    @Test
    void localIdWithSurroundingSpacesIsRefusedBecauseItWouldNotReadBackUnchanged() {
        BackupManifest unsafe = manifest("c", vm("utm", " padded.utm", "padded", "utm__padded"));

        assertThrows(IllegalArgumentException.class, () -> writer.write(unsafe));
    }

    @Test
    void directoryEscapingTheBatchIsRejectedByTheEntryItself() {
        VmId id = new VmId("utm", "Linux.utm");

        assertThrows(IllegalArgumentException.class,
                () -> new BackupManifest.VmEntry(id, "Linux", "../escape"));
    }

    @Test
    void manifestWithDuplicateDirectoriesCannotBeConstructed() {
        BackupManifest.VmEntry first = vm("utm", "A.utm", "A", "same");
        BackupManifest.VmEntry second = vm("qemu", "A", "A", "SAME");

        assertThrows(IllegalArgumentException.class, () -> manifest("c", first, second));
    }
}
