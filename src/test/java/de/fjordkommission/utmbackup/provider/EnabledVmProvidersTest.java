package de.fjordkommission.utmbackup.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnabledVmProvidersTest {

    @Test
    void defaultsToUtmWhenNeitherPropertyIsPresent() {
        EnabledVmProviders enabled = EnabledVmProviders.resolve(null, null);

        assertEquals(List.of("utm"), enabled.ids());
        assertFalse(enabled.legacyFallback());
    }

    @Test
    void singleProviderFromVmProviders() {
        EnabledVmProviders enabled = EnabledVmProviders.resolve(List.of("utm"), null);

        assertEquals(List.of("utm"), enabled.ids());
        assertFalse(enabled.legacyFallback());
    }

    @Test
    void multipleProvidersKeepConfiguredOrder() {
        assertEquals(
                List.of("utm", "qemu"),
                EnabledVmProviders.resolve(List.of("utm", "qemu"), null).ids()
        );
        assertEquals(
                List.of("qemu", "utm"),
                EnabledVmProviders.resolve(List.of("qemu", "utm"), null).ids()
        );
    }

    @Test
    void whitespaceAndUpperCaseAreNormalized() {
        EnabledVmProviders enabled = EnabledVmProviders.resolve(
                List.of("  UTM ", " qemu"),
                null
        );

        assertEquals(List.of("utm", "qemu"), enabled.ids());
    }

    @Test
    void vmProvidersTakesPrecedenceOverLegacyProperty() {
        EnabledVmProviders enabled = EnabledVmProviders.resolve(List.of("qemu"), "utm");

        assertEquals(List.of("qemu"), enabled.ids());
        assertFalse(enabled.legacyFallback());
    }

    @Test
    void legacyPropertyIsUsedWhenVmProvidersIsAbsent() {
        EnabledVmProviders enabled = EnabledVmProviders.resolve(null, "qemu");

        assertEquals(List.of("qemu"), enabled.ids());
        assertTrue(enabled.legacyFallback());
    }

    @Test
    void legacyPropertyIsNormalizedLikeInVersion140() {
        EnabledVmProviders enabled = EnabledVmProviders.resolve(null, " UTM ");

        assertEquals(List.of("utm"), enabled.ids());
        assertTrue(enabled.legacyFallback());
    }

    @Test
    void emptyVmProvidersListCountsAsAbsent() {
        EnabledVmProviders enabled = EnabledVmProviders.resolve(List.of(), "qemu");

        assertEquals(List.of("qemu"), enabled.ids());
        assertTrue(enabled.legacyFallback());
    }

    @Test
    void duplicateIdsAfterNormalizationAreRejected() {
        List<String> providers = List.of("utm", " UTM");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> EnabledVmProviders.resolve(providers, null)
        );

        assertTrue(exception.getMessage().contains("Duplicate provider id 'utm'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankEntriesAreRejected(String blank) {
        List<String> providers = Arrays.asList("utm", blank);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> EnabledVmProviders.resolve(providers, null)
        );

        assertTrue(exception.getMessage().contains("Blank provider id in vm.providers"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"qemu_x", "my provider", "a:b", "utm.x", "ü", "qemu/x"})
    void invalidIdsAreRejectedWithTheSameRuleAsVmId(String invalid) {
        List<String> providers = List.of(invalid);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> EnabledVmProviders.resolve(providers, null)
        );

        assertTrue(exception.getMessage().contains("Invalid provider id"));
    }

    @Test
    void blankLegacyPropertyIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EnabledVmProviders.resolve(null, "  ")
        );
    }

    @Test
    void resultIsImmutable() {
        List<String> ids = EnabledVmProviders.resolve(List.of("utm"), null).ids();

        assertThrows(UnsupportedOperationException.class, () -> ids.add("qemu"));
    }
}
