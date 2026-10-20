package de.fjordkommission.utmbackup.provider;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotationMetadata;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VmProviderEnabledConditionTest {

    @Test
    void reportsClearlyWhenTheConditionIsUsedWithoutTheActivationAnnotation() {
        AnnotationMetadata metadataWithoutAnnotation = AnnotationMetadata.introspect(Object.class);

        ConditionContext context = mock(ConditionContext.class);
        VmProviderEnabledCondition condition = new VmProviderEnabledCondition();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> condition.matches(context, metadataWithoutAnnotation)
        );

        assertTrue(
                exception.getMessage().contains("@ConditionalOnVmProvider")
        );
    }
}
