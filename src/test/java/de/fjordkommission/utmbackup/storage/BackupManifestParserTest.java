package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BackupManifestParserTest {

    private final BackupManifestParser parser = new BackupManifestParser();

    /** A format-2 manifest with a utm and a qemu VM, in the layout the spec describes. */
    private static final String FORMAT_TWO = String.join("\n",
            "Backup created by UTM Backup Manager",
            "Format-Version: 2",
            "Time: 2026-10-20T12:00:00+02:00",
            "",
            "VM:",
            "Provider: utm",
            "VM-ID: ArchLinux 02.27.utm",
            "Name: ArchLinux 02.27",
            "Directory: utm__ArchLinux-02.27",
            "",
            "VM:",
            "Provider: qemu",
            "VM-ID: ArchLinux",
            "Name: Arch Linux",
            "Directory: qemu__ArchLinux",
            "",
            "Comment:",
            "Before kernel update",
            "");

    // ------------------------------------------------------------------
    // Legacy format 1
    // ------------------------------------------------------------------

    @Test
    void fileWithoutFormatVersionIsFormatOne() {
        BackupManifest manifest = parsed("""
                Backup created by VM Backup Manager
                Time: 2026-09-01T12:00:00
                VMs:
                test-linux-vm

                Comment:
                Before kernel update
                """);

        assertEquals(BackupFormat.LEGACY, manifest.formatVersion());
        assertEquals("Before kernel update", manifest.comment());
        assertTrue(manifest.vms().isEmpty());
        assertEquals(Optional.empty(), manifest.backupTime());
    }

    static Stream<Arguments> legacyCommentCases() {
        return Stream.of(
                Arguments.of("""
                        Backup created by VM Backup Manager
                        Time: 2026-09-01T12:00:00

                        Comment:
                        No VMs section here
                        """, "No VMs section here"),
                Arguments.of("""
                        Backup created by VM Backup Manager
                        Comment:
                        VM:
                        VMs:
                        VM-ID: looks like a field but is comment text
                        """, "VM:\nVMs:\nVM-ID: looks like a field but is comment text"),
                Arguments.of("""
                        Backup created by VM Backup Manager
                        Comment:
                        Format-Version: 2
                        """, "Format-Version: 2"),
                Arguments.of("Test backup metadata", ""),
                Arguments.of("""
                        Backup created by VM Backup Manager
                        Comment:
                          first line
                        second line

                        """, "first line\nsecond line"),
                Arguments.of("Format-Version: 1\nComment:\nold style\n", "old style")
        );
    }

    /** Legacy comment extraction, including comments that look like manifest syntax. */
    @ParameterizedTest
    @MethodSource("legacyCommentCases")
    void legacyCommentIsExtractedAsInVersion140(String text, String expectedComment) {
        BackupManifest manifest = parsed(text);

        assertEquals(BackupFormat.LEGACY, manifest.formatVersion());
        assertEquals(expectedComment, manifest.comment());
    }

    @Test
    void legacyVmsSectionIsNotInterpretedAsFormatTwoEntries() {
        BackupManifest manifest = parsed("""
                Backup created by VM Backup Manager
                VMs:
                Linux.utm
                Provider: utm
                VM-ID: Linux.utm

                Comment:
                Legacy comment
                """);

        assertEquals(BackupFormat.LEGACY, manifest.formatVersion());
        assertTrue(manifest.vms().isEmpty());
        assertEquals("Legacy comment", manifest.comment());
    }

    // ------------------------------------------------------------------
    // Format 2: valid manifests
    // ------------------------------------------------------------------

    @Test
    void parsesProviderAwareManifest() {
        BackupManifest manifest = parsed(FORMAT_TWO);

        assertEquals(BackupFormat.PROVIDER_AWARE, manifest.formatVersion());
        assertEquals(Optional.of(OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.ofHours(2))),
                manifest.backupTime());
        assertEquals("Before kernel update", manifest.comment());
        assertEquals(
                List.of(
                        new BackupManifest.VmEntry(new VmId("utm", "ArchLinux 02.27.utm"), "ArchLinux 02.27", "utm__ArchLinux-02.27"),
                        new BackupManifest.VmEntry(new VmId("qemu", "ArchLinux"), "Arch Linux", "qemu__ArchLinux")
                ),
                manifest.vms()
        );
    }

    @Test
    void sameLocalIdUnderTwoProvidersStaysDistinct() {
        String text = FORMAT_TWO.replace("VM-ID: ArchLinux 02.27.utm", "VM-ID: ArchLinux")
                .replace("Directory: utm__ArchLinux-02.27", "Directory: utm__ArchLinux");

        BackupManifest manifest = parsed(text);

        assertEquals(
                List.of(new VmId("utm", "ArchLinux"), new VmId("qemu", "ArchLinux")),
                manifest.vms().stream().map(BackupManifest.VmEntry::id).toList()
        );
    }

    @Test
    void spacesDotsAndColonsInLocalIdsAreKept() {
        String text = FORMAT_TWO.replace("VM-ID: ArchLinux 02.27.utm", "VM-ID: a b.c:d:e");

        BackupManifest manifest = parsed(text);

        assertEquals("a b.c:d:e", manifest.vms().getFirst().id().localId());
    }

    @Test
    void offsetDateTimeKeepsInstantAndOffset() {
        String text = FORMAT_TWO.replace("2026-10-20T12:00:00+02:00", "2026-10-20T06:30:00-05:30");

        OffsetDateTime time = parsed(text).backupTime().orElseThrow();

        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30), time.getOffset());
        assertEquals(OffsetDateTime.of(2026, 10, 20, 6, 30, 0, 0, ZoneOffset.ofHoursMinutes(-5, -30)), time);
    }

    @Test
    void arbitraryMultiLineCommentIsReturnedVerbatim() {
        String text = FORMAT_TWO.replace(
                "Before kernel update\n",
                "first\n\n  indented line\n- bullet: with colon\nlast\n"
        );

        assertEquals("first\n\n  indented line\n- bullet: with colon\nlast",
                parsed(text).comment());
    }

    @Test
    void commentMayContainVmAndVmsMarkersWithoutChangingEntries() {
        String text = FORMAT_TWO.replace(
                "Before kernel update\n",
                "VM:\nVMs:\nVM-ID: injected\nProvider: fake\n"
        );

        BackupManifest manifest = parsed(text);

        assertEquals("VM:\nVMs:\nVM-ID: injected\nProvider: fake", manifest.comment());
        assertEquals(2, manifest.vms().size());
    }

    @Test
    void unknownFieldsTopLevelAndInsideBlocksAreIgnored() {
        String text = FORMAT_TWO
                .replace("Format-Version: 2\n", "Format-Version: 2\nFuture-Field: anything\n")
                .replace("Directory: qemu__ArchLinux\n", "Directory: qemu__ArchLinux\nColor: red\nFormat-Version: 9\n");

        BackupManifest manifest = parsed(text);

        assertEquals(2, manifest.vms().size());
        assertEquals(BackupFormat.PROVIDER_AWARE, manifest.formatVersion());
    }

    @Test
    void missingNameFallsBackToLocalId() {
        String text = FORMAT_TWO.replace("Name: Arch Linux\n", "");

        assertEquals("ArchLinux", parsed(text).vms().get(1).name());
    }

    @Test
    void utf8ByteOrderMarkIsIgnored() {
        assertEquals(parsed(FORMAT_TWO), parsed("\uFEFF" + FORMAT_TWO));
    }

    @Test
    void crlfLineEndingsAreAccepted() {
        assertEquals(parsed(FORMAT_TWO), parsed(FORMAT_TWO.replace("\n", "\r\n")));
    }

    @Test
    void formatVersionOneBecomesLegacyEvenWithBomAndCrlf() {
        BackupManifest manifest = parsed("\uFEFFComment:\r\nold\r\n");

        assertEquals(BackupFormat.LEGACY, manifest.formatVersion());
        assertEquals("old", manifest.comment());
    }

    // ------------------------------------------------------------------
    // Format 2: validation (malformed content is Invalid, never format 1)
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Provider", "VM-ID", "Directory"})
    void missingRequiredVmFieldIsInvalid(String key) {
        String text = FORMAT_TWO.lines()
                .filter(line -> !line.startsWith(key + ":"))
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();

        assertInvalid(text);
    }

    @Test
    void duplicateVmIdInOneBatchIsInvalid() {
        String text = FORMAT_TWO.replace("VM-ID: ArchLinux\n", "VM-ID: ArchLinux 02.27.utm\n")
                .replace("Provider: qemu", "Provider: utm");

        assertInvalid(text);
    }

    @Test
    void duplicateDirectoryIsInvalidEvenWhenOnlyCaseDiffers() {
        String text = FORMAT_TWO.replace("Directory: qemu__ArchLinux", "Directory: UTM__ARCHLINUX-02.27");

        assertInvalid(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil", "a/b", "a\\b", "..", ".", "x..y", ""})
    void directoryMustBeOneSafePathElement(String directory) {
        String text = FORMAT_TWO.replace("Directory: utm__ArchLinux-02.27", "Directory: " + directory);

        assertInvalid(text);
    }

    @Test
    void unsupportedFutureVersionIsReportedExplicitly() {
        String text = FORMAT_TWO.replace("Format-Version: 2", "Format-Version: 3");

        BackupManifestResult result = parser.parse(text);

        assertEquals(new BackupManifestResult.UnsupportedVersion(3), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"two", "2.0", "-1", "0", "99999999999"})
    void malformedFormatVersionIsInvalidAndNeverFormatOne(String version) {
        String text = FORMAT_TWO.replace("Format-Version: 2", "Format-Version: " + version);

        assertInvalid(text);
    }

    @Test
    void duplicateFormatVersionIsInvalid() {
        String text = FORMAT_TWO.replace("Format-Version: 2\n", "Format-Version: 2\nFormat-Version: 2\n");

        assertInvalid(text);
    }

    @Test
    void formatTwoWithoutTimeIsInvalid() {
        String text = FORMAT_TWO.replace("Time: 2026-10-20T12:00:00+02:00\n", "");

        assertInvalid(text);
    }

    @Test
    void formatTwoTimeWithoutOffsetIsInvalid() {
        String text = FORMAT_TWO.replace("2026-10-20T12:00:00+02:00", "2026-10-20T12:00:00");

        assertInvalid(text);
    }

    @Test
    void vmFieldOutsideAVmBlockIsInvalid() {
        String text = FORMAT_TWO.replace("Time: 2026-10-20T12:00:00+02:00\n", "Time: 2026-10-20T12:00:00+02:00\nProvider: utm\n");

        assertInvalid(text);
    }

    @Test
    void repeatedFieldInsideOneBlockIsInvalid() {
        String text = FORMAT_TWO.replace("Name: ArchLinux 02.27\n", "Name: ArchLinux 02.27\nName: other\n");

        assertInvalid(text);
    }

    @Test
    void invalidProviderIdIsInvalid() {
        String text = FORMAT_TWO.replace("Provider: qemu", "Provider: Qemu_X");

        assertInvalid(text);
    }

    @Test
    void formatTwoManifestIsNeverReturnedAsFormatOneOnFailure() {
        String text = FORMAT_TWO.replace("Directory: qemu__ArchLinux", "Directory: ../escape");

        BackupManifestResult result = parser.parse(text);

        assertInstanceOf(BackupManifestResult.Invalid.class, result);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private BackupManifest parsed(String text) {
        BackupManifestResult result = parser.parse(text);

        assertInstanceOf(BackupManifestResult.Parsed.class, result, "expected a parsed manifest: " + result);

        return ((BackupManifestResult.Parsed) result).manifest();
    }

    private void assertInvalid(String text) {
        BackupManifestResult result = parser.parse(text);

        assertInstanceOf(BackupManifestResult.Invalid.class, result, "expected Invalid: " + result);
    }
}
