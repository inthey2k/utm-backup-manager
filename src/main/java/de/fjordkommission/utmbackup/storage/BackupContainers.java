package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the container directory of each VM in a format-2 batch.
 *
 * <p>A container name is {@code <providerId>__<safe local id>}. It is only a
 * filesystem name. Identity always comes from the manifest, so the name is never
 * parsed back. Characters outside {@code [A-Za-z0-9_-]} become {@code -}. Dots are
 * replaced too, so a name can never be {@code .} or {@code ..} or contain a
 * separator. A name that had to be changed, or that collides with another name in
 * the batch when compared case-insensitively (macOS), gets a short hash of the
 * canonical id appended. The hash makes the name unique without relying on the
 * order of the batch.
 */
public final class BackupContainers {

    /** Longest local-id part kept before truncation, so names stay well below path limits. */
    static final int MAX_LOCAL_PART_LENGTH = 64;

    private static final int HASH_HEX_DIGITS = 8;

    private BackupContainers() {
    }

    /**
     * Assigns one container name per VM. Names are chosen in canonical-id order, so
     * the same set of VMs always produces the same names.
     *
     * @throws IllegalStateException if a unique name cannot be assigned
     */
    public static Map<VmId, String> assign(Collection<VmId> ids) {
        Map<VmId, String> names = new HashMap<>();
        Set<String> used = new HashSet<>();

        List<VmId> ordered = ids.stream()
                .sorted(Comparator.comparing(VmId::toString))
                .toList();

        for (VmId id : ordered) {
            String safeLocalId = sanitize(id.localId());
            String name = id.providerId() + "__" + safeLocalId;

            boolean changed = !safeLocalId.equals(id.localId());

            if (changed || used.contains(lowerCase(name))) {
                name = name + "-" + shortHash(id);
            }

            if (!used.add(lowerCase(name))) {
                throw new IllegalStateException("Could not assign a unique backup container for " + id);
            }

            names.put(id, name);
        }

        return names;
    }

    /** Replaces every character outside the safe set and truncates long values. */
    static String sanitize(String localId) {
        StringBuilder safe = new StringBuilder();

        for (char character : localId.toCharArray()) {
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '-';

            safe.append(allowed ? character : '-');
        }

        return safe.length() > MAX_LOCAL_PART_LENGTH
                ? safe.substring(0, MAX_LOCAL_PART_LENGTH)
                : safe.toString();
    }

    private static String shortHash(VmId id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(id.toString().getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest).substring(0, HASH_HEX_DIGITS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String lowerCase(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
