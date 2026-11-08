package de.fjordkommission.utmbackup.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A provider-owned identity file kept inside a VM directory. It gives a VM without a
 * native stable id a persistent generated id that moves with the directory, so the id
 * survives restarts, renames of the directory and display-name changes.
 *
 * <p>The file is a small plain-text record, {@code format=1}, {@code provider=<id>},
 * {@code id=<uuid>}. It is the only thing written into the VM directory; disk images are
 * never touched. Hidden, so the provider's listing ignores it.
 *
 * <p>Safety rules:
 * <ul>
 *   <li>Created once, atomically: the content is written to a temporary file and linked to
 *       the target, which fails if the target exists. An existing file is never replaced.</li>
 *   <li>Unreadable, malformed, or foreign (another provider's) content is ignored, and the
 *       caller falls back to the directory name. Nothing is guessed or overwritten.</li>
 *   <li>A copy of the directory carries the same id. Two live copies then share an id, and
 *       the inventory hides both until one copy's file is removed.</li>
 * </ul>
 */
public final class VmIdentitySidecar {

    public static final String FILE_NAME = ".utm-backup-manager-identity";

    private static final String FORMAT_LINE = "format=1";
    private static final String PROVIDER_KEY = "provider=";
    private static final String ID_KEY = "id=";
    private static final long MAX_BYTES = 512;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private VmIdentitySidecar() {
    }

    /**
     * Returns the id stored for this provider, creating it when the directory has none.
     * Empty when the directory is read-only, or when an existing file is unusable.
     */
    public static Optional<String> readOrCreate(Path vmDirectory, String providerId) {
        Optional<String> existing = read(vmDirectory, providerId);

        return existing.isPresent() ? existing : create(vmDirectory, providerId);
    }

    /**
     * Returns the id stored in a VM directory for this provider, or empty when there is
     * no usable file. Never throws.
     */
    public static Optional<String> read(Path vmDirectory, String providerId) {
        Path file = vmDirectory.resolve(FILE_NAME);

        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                return Optional.empty();
            }

            return parse(Files.readString(file, StandardCharsets.UTF_8), providerId);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the id of a VM package inside a backup container. Only a single agreeing id
     * counts; packages that disagree make the identity unknown.
     */
    public static Optional<String> readContainer(Path container, String providerId) {
        if (!Files.isDirectory(container)) {
            return Optional.empty();
        }

        List<String> ids;

        try (Stream<Path> children = Files.list(container)) {
            ids = children
                    .filter(Files::isDirectory)
                    .map(child -> read(child, providerId))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }

        Set<String> distinct = new HashSet<>(ids);

        return distinct.size() == 1 ? Optional.of(distinct.iterator().next()) : Optional.empty();
    }

    private static Optional<String> create(Path vmDirectory, String providerId) {
        String id = UUID.randomUUID().toString();
        String text = FORMAT_LINE + "\n" + PROVIDER_KEY + providerId + "\n" + ID_KEY + id + "\n";
        Path target = vmDirectory.resolve(FILE_NAME);
        Path temporary = null;

        try {
            temporary = Files.createTempFile(vmDirectory, ".utm-backup-manager-identity-", ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            publishWithoutReplacing(temporary, target);
            return Optional.of(id);
        } catch (FileAlreadyExistsException e) {
            // Another instance created the file first; its id is the one to use.
            return read(vmDirectory, providerId);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * Makes the target visible only if it does not exist yet. A hard link fails atomically
     * when the target exists. File systems without hard links fall back to a move that
     * checks for the target first.
     */
    private static void publishWithoutReplacing(Path temporary, Path target) throws IOException {
        try {
            Files.createLink(target, temporary);
        } catch (UnsupportedOperationException e) {
            if (Files.exists(target)) {
                throw new FileAlreadyExistsException(target.toString());
            }

            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static Optional<String> parse(String text, String providerId) {
        List<String> lines = text.lines().toList();

        if (lines.size() != 3 || !FORMAT_LINE.equals(lines.get(0))
                || !lines.get(1).startsWith(PROVIDER_KEY) || !lines.get(2).startsWith(ID_KEY)) {
            return Optional.empty();
        }

        if (!providerId.equals(lines.get(1).substring(PROVIDER_KEY.length()))) {
            return Optional.empty();
        }

        String id = lines.get(2).substring(ID_KEY.length());

        return UUID_PATTERN.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    private static void deleteQuietly(Path temporary) {
        if (temporary == null) {
            return;
        }

        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // A leftover hidden temporary file is harmless and is not a VM.
        }
    }
}
