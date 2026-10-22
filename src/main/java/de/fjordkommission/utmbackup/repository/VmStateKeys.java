package de.fjordkommission.utmbackup.repository;

import de.fjordkommission.utmbackup.model.VmId;

/**
 * Classifies the keys stored in {@code vm_state}.
 *
 * <p>Canonical keys are {@code providerId:localId}. Keys written before Phase 7a are
 * the local ids of UTM VMs, which are folder names and may themselves contain colons.
 * A key counts as canonical only if its prefix is a valid provider id and something
 * follows the colon. A key with a valid prefix and nothing after the colon is
 * malformed and is never rewritten.
 */
final class VmStateKeys {

    /** The provider that owned every key before canonical keys existed. */
    static final String LEGACY_PROVIDER_ID = "utm";

    private VmStateKeys() {
    }

    static boolean isCanonical(String key) {
        int separator = key.indexOf(':');

        return separator > 0
                && VmId.isValidProviderId(key.substring(0, separator))
                && !key.substring(separator + 1).isBlank();
    }

    static boolean isMalformed(String key) {
        if (key.isBlank()) {
            return true;
        }

        int separator = key.indexOf(':');

        return separator > 0
                && VmId.isValidProviderId(key.substring(0, separator))
                && key.substring(separator + 1).isBlank();
    }

    static String canonicalOfLegacy(String legacyKey) {
        return LEGACY_PROVIDER_ID + ":" + legacyKey;
    }
}
