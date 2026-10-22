package de.fjordkommission.utmbackup.provider;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates the annotated provider bean only when its provider id is enabled in
 * {@code vm.providers}, or in the deprecated {@code vm.provider}, or by default.
 *
 * A disabled provider is never instantiated, so its configuration may be absent.
 * The annotation value must equal the provider's {@link VmProvider#providerId()}.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(VmProviderEnabledCondition.class)
public @interface ConditionalOnVmProvider {

    /**
     * The provider id, for example {@code utm}.
     */
    String value();
}
