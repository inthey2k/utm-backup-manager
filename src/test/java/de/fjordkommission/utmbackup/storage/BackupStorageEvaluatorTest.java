package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BackupStorageEvaluatorTest {

    private static final String SUPPORTED_MARKER = String.join("\n",
            "format.version=2",
            "application.name=UTM Backup Manager",
            "created.at=2026-10-21T12:00:00+02:00",
            "created.by.version=1.5.0",
            "last.written.by.version=1.5.0",
            "");

    @TempDir
    Path root;

    private final BackupStorageEvaluator evaluator = new BackupStorageEvaluator();
    private final BackupManifestReader manifestReader = new BackupManifestReader();

    @Test
    void noMarkerAndOnlyFormatOneBatchesIsLegacyAndWritable() throws IOException {
        legacyBatch("2026-09-01_1200", "Comment:\nold backup\n");
        Files.createDirectories(root.resolve("2026-09-02_1200").resolve("VMs").resolve("Linux.utm"));

        BackupStorageStatus status = evaluator.evaluate(root);

        assertEquals(BackupStorageCompatibility.LEGACY, status.compatibility());
        assertTrue(status.isWritable());
    }

    @Test
    void noMarkerButAFormatTwoBatchIsReadOnlyInconsistentNotLegacy() throws IOException {
        formatTwoBatch("2026-10-20_1200");

        BackupStorageStatus status = evaluator.evaluate(root);

        assertEquals(BackupStorageCompatibility.READ_ONLY_INCONSISTENT, status.compatibility());
        assertFalse(status.isWritable());
        assertTrue(status.reason().contains("2026-10-20_1200"), status.reason());
        assertTrue(status.reason().contains("storage format 2"), status.reason());
    }

    @Test
    void noMarkerAndAnUnreadableManifestIsReadOnlyInconsistent() throws IOException {
        Path batch = Files.createDirectories(root.resolve("2026-09-01_1200"));
        Files.writeString(batch.resolve(BackupManifestReader.FILE_NAME), "Format-Version: two\n");

        BackupStorageStatus status = evaluator.evaluate(root);

        assertEquals(BackupStorageCompatibility.READ_ONLY_INCONSISTENT, status.compatibility());
        assertFalse(status.isWritable());
    }

    @Test
    void supportedMarkerIsWritable() throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), SUPPORTED_MARKER);

        BackupStorageStatus status = evaluator.evaluate(root);

        assertEquals(BackupStorageCompatibility.SUPPORTED, status.compatibility());
        assertTrue(status.isWritable());
    }

    @Test
    void newerMarkerIsReadOnly() throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME),
                SUPPORTED_MARKER.replace("format.version=2", "format.version=3"));

        BackupStorageStatus status = evaluator.evaluate(root);

        assertEquals(BackupStorageCompatibility.READ_ONLY_NEWER_FORMAT, status.compatibility());
        assertFalse(status.isWritable());
        assertTrue(status.reason().contains("storage format 3"), status.reason());
    }

    @Test
    void invalidMarkerIsReadOnlyAndNeverDowngradedToLegacy() throws IOException {
        legacyBatch("2026-09-01_1200", "Comment:\nold\n");
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), "format.version=two\n");

        BackupStorageStatus status = evaluator.evaluate(root);

        assertEquals(BackupStorageCompatibility.READ_ONLY_INVALID_METADATA, status.compatibility());
        assertFalse(status.isWritable());
    }

    @Test
    void supportedMarkerWithMixedBatchesStaysWritable() throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), SUPPORTED_MARKER);
        legacyBatch("2026-09-01_1200", "Comment:\nold\n");
        formatTwoBatch("2026-10-20_1200");

        assertEquals(BackupStorageCompatibility.SUPPORTED, evaluator.evaluate(root).compatibility());
    }

    @Test
    void rootMarkerNeverChangesHowABatchIsRead() throws IOException {
        // A legacy batch whose comment looks like a version line stays legacy under any marker.
        legacyBatch("2026-09-01_1200", "Comment:\nFormat-Version: 2\n");
        // A batch that declares a newer format stays newer under any marker.
        Path future = Files.createDirectories(root.resolve("2026-09-02_1200"));
        Files.writeString(future.resolve(BackupManifestReader.FILE_NAME), "Format-Version: 3\nComment:\nx\n");

        Path legacy = root.resolve("2026-09-01_1200");
        BackupManifestResult withoutMarker = manifestReader.read(legacy);
        BackupManifestResult futureWithoutMarker = manifestReader.read(future);

        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), SUPPORTED_MARKER);

        assertEquals(withoutMarker, manifestReader.read(legacy));
        assertEquals(futureWithoutMarker, manifestReader.read(future));
        assertEquals(new BackupManifestResult.Parsed(BackupManifest.legacy("Format-Version: 2")), manifestReader.read(legacy));
        assertEquals(new BackupManifestResult.UnsupportedVersion(3), manifestReader.read(future));
    }

    @Test
    void mixedMediumKeepsEachBatchFormatUnchangedUnderTheMarker() throws IOException {
        Files.writeString(root.resolve(BackupStorageMetadataReader.FILE_NAME), SUPPORTED_MARKER);
        legacyBatch("2026-09-01_1200", "Comment:\nold\n");
        formatTwoBatch("2026-10-20_1200");

        BackupManifest legacy = ((BackupManifestResult.Parsed) manifestReader.read(root.resolve("2026-09-01_1200"))).manifest();
        BackupManifest providerAware = ((BackupManifestResult.Parsed) manifestReader.read(root.resolve("2026-10-20_1200"))).manifest();

        assertEquals(BackupFormat.LEGACY, legacy.formatVersion());
        assertEquals(BackupFormat.PROVIDER_AWARE, providerAware.formatVersion());
        assertEquals(List.of(new VmId("utm", "Linux.utm")), providerAware.vms().stream().map(BackupManifest.VmEntry::id).toList());
    }

    @Test
    void missingBackupDirectoryIsUnavailable() {
        BackupStorageStatus status = evaluator.evaluate(root.resolve("not-mounted"));

        assertEquals(BackupStorageCompatibility.UNAVAILABLE, status.compatibility());
        assertFalse(status.isWritable());
    }

    @Test
    void formatTwoBatchIsReadWithItsOwnTimeRegardlessOfMarkers() throws IOException {
        formatTwoBatch("2026-10-20_1200");

        Optional<OffsetDateTime> time = ((BackupManifestResult.Parsed) manifestReader.read(root.resolve("2026-10-20_1200")))
                .manifest().backupTime();

        assertEquals(Optional.of(OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.ofHours(2))), time);
    }

    private void legacyBatch(String name, String manifest) throws IOException {
        Path batch = Files.createDirectories(root.resolve(name).resolve("VMs").resolve("Linux.utm"));
        Files.writeString(batch.getParent().getParent().resolve(BackupManifestReader.FILE_NAME), manifest);
    }

    private void formatTwoBatch(String name) throws IOException {
        Path batch = Files.createDirectories(root.resolve(name).resolve("VMs").resolve("utm__Linux"));
        BackupManifest manifest = new BackupManifest(
                BackupFormat.PROVIDER_AWARE,
                Optional.of(OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.ofHours(2))),
                "two-format batch",
                List.of(new BackupManifest.VmEntry(new VmId("utm", "Linux.utm"), "Linux", "utm__Linux"))
        );
        Files.writeString(batch.getParent().getParent().resolve(BackupManifestReader.FILE_NAME),
                new BackupManifestWriter().write(manifest));
    }
}
