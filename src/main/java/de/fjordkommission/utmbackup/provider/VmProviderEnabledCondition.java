package de.fjordkommission.utmbackup.provider;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

/**
 * Evaluates {@link ConditionalOnVmProvider} against the resolved provider list.
 *
 * Invalid provider configuration is reported by throwing from this condition,
 * so the application fails at startup with the resolver's message.
 */
class VmProviderEnabledCondition implements Condition {

    @Override
    public boolean matches(
            ConditionContext context,
            AnnotatedTypeMetadata metadata
    ) {
        // Unlike getAnnotationAttributes(), this never returns null: an absent annotation
        // is reported through isPresent(), so the lookup cannot cause a NullPointerException.
        MergedAnnotation<ConditionalOnVmProvider> annotation =
                metadata.getAnnotations().get(ConditionalOnVmProvider.class);

        if (!annotation.isPresent()) {
            throw new IllegalStateException(
                    "VmProviderEnabledCondition must be used through @ConditionalOnVmProvider"
            );
        }

        String providerId = annotation.getString("value");

        Binder binder = Binder.get(context.getEnvironment());

        List<String> providers = binder
                .bind("vm.providers", Bindable.listOf(String.class))
                .orElse(null);
        String legacyProvider = binder
                .bind("vm.provider", String.class)
                .orElse(null);

        return EnabledVmProviders.resolve(providers, legacyProvider)
                .ids()
                .contains(providerId);
    }
}
