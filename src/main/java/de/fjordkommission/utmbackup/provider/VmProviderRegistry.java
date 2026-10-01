package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.config.VmProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selects the configured VM provider automatically
 * from the provider implementations registered with Spring.
 */
@Component
public class VmProviderRegistry {

    private final Map<String, VmProvider> providers;
    private final String configuredProvider;

    public VmProviderRegistry(
            List<VmProvider> providers,
            VmProperties vmProperties
    ) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        provider -> normalize(provider.providerId()),
                        Function.identity()
                ));

        this.configuredProvider = normalize(vmProperties.provider());
    }

    /**
     * Returns the provider selected through the vm.provider property.
     */
    public VmProvider current() {
        VmProvider provider = providers.get(configuredProvider);

        if (provider == null) {
            throw new IllegalStateException(
                    "No VM provider registered for '" + configuredProvider
                            + "'. Available providers: "
                            + String.join(", ", providers.keySet())
            );
        }

        return provider;
    }

    private String normalize(String providerId) {
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}