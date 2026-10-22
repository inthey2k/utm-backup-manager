package de.fjordkommission.utmbackup.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Decides the compatibility state of a backup medium. This is the only place that
 * contains the compatibility rules.
 *
 * <p>The root marker describes the highest format written to the medium. It never
 * changes how an individual batch is read, and a supported root does not make every
 * batch format 2. Batch formats are read by {@link BackupManifestReader}.
 */
public final class BackupStorageEvaluator {

    private static final Pattern BATCH_DIRECTORY = Pattern.compile("^20\\d\\d-.*");
    private static final String BATCH_PREFIX = "batch ";

    private final BackupStorageMetadataReader metadataReader = new BackupStorageMetadataReader();
    private final BackupManifestReader manifestReader = new BackupManifestReader();

    public BackupStorageStatus evaluate(Path root) {
        if (!Files.isDirectory(root)) {
            return new BackupStorageStatus(
                    BackupStorageCompatibility.UNAVAILABLE,
                    "the backup directory " + root + " is not available"
            );
        }

        return switch (metadataReader.read(root)) {
            case BackupStorageMetadataResult.Absent() -> evaluateUnmarked(root);
            case BackupStorageMetadataResult.Present(BackupRootMetadata metadata) -> new BackupStorageStatus(
                    BackupStorageCompatibility.SUPPORTED,
                    "the storage format " + metadata.formatVersion() + " is supported"
            );
            case BackupStorageMetadataResult.UnsupportedVersion(int version) -> new BackupStorageStatus(
                    BackupStorageCompatibility.READ_ONLY_NEWER_FORMAT,
                    "the medium uses storage format " + version + ", which is newer than the supported format "
                            + BackupFormat.CURRENT + "; upgrade UTM Backup Manager to write to it"
            );
            case BackupStorageMetadataResult.Invalid(String reason) -> new BackupStorageStatus(
                    BackupStorageCompatibility.READ_ONLY_INVALID_METADATA,
                    "the root metadata cannot be interpreted safely (" + reason + ")"
            );
        };
    }

    /**
     * Without root metadata the medium is legacy only if no batch declares anything
     * other than format 1. Otherwise it is inconsistent and read-only.
     */
    private BackupStorageStatus evaluateUnmarked(Path root) {
        Optional<String> nonLegacyBatch = firstNonLegacyBatch(root);

        if (nonLegacyBatch.isPresent()) {
            return new BackupStorageStatus(
                    BackupStorageCompatibility.READ_ONLY_INCONSISTENT,
                    "the medium has no root metadata, but " + nonLegacyBatch.get()
            );
        }

        return new BackupStorageStatus(BackupStorageCompatibility.LEGACY, "the medium uses the legacy format");
    }

    private Optional<String> firstNonLegacyBatch(Path root) {
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> BATCH_DIRECTORY.matcher(path.getFileName().toString()).matches())
                    .map(this::nonLegacyReason)
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IOException e) {
            return Optional.of("the backup directory cannot be listed: " + e.getMessage());
        }
    }

    private Optional<String> nonLegacyReason(Path batch) {
        String name = batch.getFileName().toString();

        return switch (manifestReader.read(batch)) {
            case BackupManifestResult.Parsed(BackupManifest manifest)
                    when manifest.formatVersion() == BackupFormat.LEGACY -> Optional.empty();
            case BackupManifestResult.Parsed(BackupManifest manifest) -> Optional.of(
                    BATCH_PREFIX + name + " uses storage format " + manifest.formatVersion()
            );
            case BackupManifestResult.UnsupportedVersion(int version) -> Optional.of(
                    BATCH_PREFIX + name + " declares storage format " + version
            );
            case BackupManifestResult.Invalid(String reason) -> Optional.of(
                    BATCH_PREFIX + name + " has a manifest that cannot be read (" + reason + ")"
            );
        };
    }
}
