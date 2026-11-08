package de.fjordkommission.utmbackup.storage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads and writes {@code VM-IDENTITIES.json} in the backup root.
 *
 * <p>Reading never fails the caller: a missing file is an empty registry, and a file that
 * cannot be parsed, has an unknown schema version or contains conflicting entries is
 * reported as {@link ReadResult.Unusable}. Such a file is never overwritten, so a newer
 * version of the application or a damaged file cannot lose its identities.
 *
 * <p>Writes go through a temporary file and are atomic, like the root metadata marker.
 */
public final class VmIdentityFile {

    public static final String FILE_NAME = "VM-IDENTITIES.json";

    /** Unknown properties are rejected, so a file from a newer format is never rewritten without them. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private VmIdentityFile() {
    }

    /** Outcome of reading the registry file. */
    public sealed interface ReadResult {

        /** No file yet: a fresh installation or a backup root that never had a backup. */
        record Absent() implements ReadResult {
        }

        record Loaded(VmIdentityRegistry registry) implements ReadResult {
        }

        /** Present but not usable; it must be left untouched. */
        record Unusable(String reason) implements ReadResult {
        }
    }

    public static ReadResult read(Path root) {
        Path file = root.resolve(FILE_NAME);

        String text;

        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new ReadResult.Absent();
        } catch (IOException e) {
            return new ReadResult.Unusable("could not be read: " + e.getMessage());
        }

        VmIdentityRegistry registry;

        try {
            registry = MAPPER.readValue(text, VmIdentityRegistry.class);
        } catch (JacksonException e) {
            return new ReadResult.Unusable("is not valid identity data: " + e.getMessage());
        }

        if (registry.schemaVersion() != VmIdentityRegistry.SCHEMA_VERSION) {
            return new ReadResult.Unusable(
                    "uses schema version " + registry.schemaVersion()
                            + "; this application supports " + VmIdentityRegistry.SCHEMA_VERSION);
        }

        String conflict = conflictOf(registry);

        return conflict == null
                ? new ReadResult.Loaded(registry)
                : new ReadResult.Unusable(conflict);
    }

    /**
     * Writes the registry atomically. The caller must have checked that the storage is
     * writable.
     *
     * @throws IOException if the temporary file or the replacement fails
     */
    public static void write(Path root, VmIdentityRegistry registry) throws IOException {
        String text = MAPPER.writeValueAsString(registry) + "\n";
        Path target = root.resolve(FILE_NAME);
        Path temporary = null;

        try {
            temporary = Files.createTempFile(root, ".VM-IDENTITIES-", ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            AtomicFiles.replace(temporary, target);
        } finally {
            AtomicFiles.deleteQuietly(temporary);
        }
    }

    /**
     * Two entries for the same canonical VM, or entries with blank ids, make the file
     * unusable: merging them would be a guess.
     */
    private static String conflictOf(VmIdentityRegistry registry) {
        Set<String> seen = new HashSet<>();

        for (VmIdentityRegistry.Entry entry : registry.identities()) {
            if (entry.providerId() == null || entry.stableLocalId() == null
                    || entry.stableLocalId().isBlank() || entry.source() == null) {
                return "contains an incomplete identity entry";
            }

            if (!seen.add(entry.providerId() + ':' + entry.stableLocalId())) {
                return "contains the identity " + entry.providerId() + ':' + entry.stableLocalId() + " twice";
            }
        }

        return null;
    }
}
