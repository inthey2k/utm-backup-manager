package de.fjordkommission.utmbackup.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Canonical, provider-qualified identity of a VM, serialized as
 * {@code providerId:localId}, for example {@code utm:ArchLinux 02.27.utm}.
 *
 * The provider id never contains a colon, so parsing splits on the first colon
 * only and local ids may contain colons. The local id is the provider-local
 * {@link LocalVm#id()} and is meaningful only within its provider.
 */
public record VmId(String providerId, String localId) {

    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9-]+");
    private static final char SEPARATOR = ':';

    /**
     * Returns whether the value is a valid provider id: lower-case letters,
     * digits and hyphens only. Configuration and provider registration use the
     * same rule.
     */
    public static boolean isValidProviderId(String providerId) {
        return providerId != null && PROVIDER_ID.matcher(providerId).matches();
    }

    public VmId {
        if (!isValidProviderId(providerId)) {
            throw new IllegalArgumentException(
                    "Invalid provider id: " + providerId
            );
        }

        if (localId == null || localId.isBlank()) {
            throw new IllegalArgumentException(
                    "Local VM id must not be blank"
            );
        }
    }

    /**
     * Parses a serialized {@code providerId:localId} value.
     */
    public static VmId parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("VM id must not be null");
        }

        int separator = value.indexOf(SEPARATOR);

        if (separator < 0) {
            throw new IllegalArgumentException(
                    "VM id must have the form providerId:localId: " + value
            );
        }

        return new VmId(
                value.substring(0, separator),
                value.substring(separator + 1)
        );
    }

    /**
     * Parses a serialized value for request handling, returning empty instead of
     * throwing when the value is malformed.
     */
    public static Optional<VmId> tryParse(String value) {
        try {
            return Optional.of(parse(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return providerId + SEPARATOR + localId;
    }
}
