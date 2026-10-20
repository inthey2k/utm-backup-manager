package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.model.VmId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The effective, ordered list of enabled VM provider ids.
 *
 * This is the only place that implements the provider-selection rules, which
 * are applied in this order:
 * <ol>
 *   <li>{@code vm.providers}, a comma-separated list. An empty list counts as absent.</li>
 *   <li>{@code vm.provider}, the deprecated single-provider property from 1.4.x.</li>
 *   <li>{@code utm}, the default.</li>
 * </ol>
 *
 * Ids are trimmed and lower-cased before validation, so {@code UTM} is accepted
 * as {@code utm}. This matches the normalization that 1.4.x applied to
 * {@code vm.provider}, so existing configurations keep working. Blank or
 * duplicate entries and ids that fail {@link VmId#isValidProviderId(String)} are
 * rejected with an {@link IllegalArgumentException}.
 *
 * @param ids            enabled provider ids, in configured order, never empty
 * @param legacyFallback true when the deprecated {@code vm.provider} was used
 */
public record EnabledVmProviders(List<String> ids, boolean legacyFallback) {

    public static final String DEFAULT_PROVIDER = "utm";

    public EnabledVmProviders {
        ids = List.copyOf(ids);
    }

    /**
     * Resolves the enabled providers from the raw configuration values.
     *
     * @param providers      value of {@code vm.providers}, or null when absent
     * @param legacyProvider value of {@code vm.provider}, or null when absent
     */
    public static EnabledVmProviders resolve(
            List<String> providers,
            String legacyProvider
    ) {
        if (providers != null && !providers.isEmpty()) {
            return new EnabledVmProviders(normalizeAll(providers), false);
        }

        if (legacyProvider != null) {
            return new EnabledVmProviders(
                    List.of(normalize(legacyProvider, "vm.provider")),
                    true
            );
        }

        return new EnabledVmProviders(List.of(DEFAULT_PROVIDER), false);
    }

    private static List<String> normalizeAll(List<String> providers) {
        Set<String> ids = new LinkedHashSet<>();

        for (String provider : providers) {
            String id = normalize(provider, "vm.providers");

            if (!ids.add(id)) {
                throw new IllegalArgumentException(
                        "Duplicate provider id '" + id + "' in vm.providers"
                );
            }
        }

        return new ArrayList<>(ids);
    }

    private static String normalize(String raw, String property) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Blank provider id in " + property
            );
        }

        String id = raw.trim().toLowerCase(Locale.ROOT);

        if (!VmId.isValidProviderId(id)) {
            throw new IllegalArgumentException(
                    "Invalid provider id '" + raw.trim() + "' in " + property
                            + ": use only letters, digits and hyphens"
            );
        }

        return id;
    }
}
