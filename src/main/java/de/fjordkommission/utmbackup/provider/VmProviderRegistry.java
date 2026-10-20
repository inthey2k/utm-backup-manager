package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.VmId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the enabled VM provider implementations, in configured order.
 *
 * Only providers whose ids are enabled are returned. Duplicate implementation
 * ids and configured ids without an implementation fail at construction time.
 * Callers that need VM data use {@code VmInventory}, which aggregates these
 * providers.
 */
@Component
public class VmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(VmProviderRegistry.class);

    private final List<VmProvider> enabledProviders;

    public VmProviderRegistry(
            List<VmProvider> providers,
            VmProperties vmProperties
    ) {
        Map<String, VmProvider> implementations = indexImplementations(providers);

        EnabledVmProviders enabled = EnabledVmProviders.resolve(
                vmProperties.providers(),
                vmProperties.provider()
        );

        if (enabled.legacyFallback()) {
            log.warn(
                    "The property vm.provider is deprecated and will be removed in a "
                            + "future release. Use vm.providers instead, for example "
                            + "vm.providers={}.",
                    enabled.ids().getFirst()
            );
        }

        List<VmProvider> resolved = new ArrayList<>();

        for (String id : enabled.ids()) {
            VmProvider provider = implementations.get(id);

            if (provider == null) {
                throw new IllegalStateException(
                        "Configured VM provider '" + id + "' has no implementation. "
                                + "Available providers: "
                                + String.join(", ", implementations.keySet())
                                + ". Check vm.providers."
                );
            }

            resolved.add(provider);
        }

        this.enabledProviders = List.copyOf(resolved);
    }

    /**
     * Returns the enabled providers in configured order.
     */
    public List<VmProvider> enabledProviders() {
        return enabledProviders;
    }

    private static Map<String, VmProvider> indexImplementations(
            List<VmProvider> providers
    ) {
        Map<String, VmProvider> implementations = new HashMap<>();

        for (VmProvider provider : providers) {
            String id = normalize(provider.providerId());

            if (!VmId.isValidProviderId(id)) {
                throw new IllegalStateException(
                        "VM provider " + provider.getClass().getName()
                                + " has an invalid provider id '"
                                + provider.providerId() + "'"
                );
            }

            VmProvider previous = implementations.putIfAbsent(id, provider);

            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate VM provider implementations for id '" + id + "': "
                                + previous.getClass().getName() + " and "
                                + provider.getClass().getName()
                );
            }
        }

        return implementations;
    }

    private static String normalize(String providerId) {
        return providerId == null
                ? ""
                : providerId.trim().toLowerCase(Locale.ROOT);
    }
}
