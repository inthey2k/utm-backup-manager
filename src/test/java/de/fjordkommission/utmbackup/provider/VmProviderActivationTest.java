package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.provider.utm.UtmProviderConfiguration;
import de.fjordkommission.utmbackup.provider.utm.UtmVmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies which provider beans are created for a given configuration.
 * Uses a bare application context, so no real hypervisor is probed and no
 * vm.utm.* values are needed unless UTM is enabled.
 */
class VmProviderActivationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProviderConfiguration.class)
            .withBean("qemuStub", VmProvider.class, () -> new StubVmProvider("qemu"))
            .withBean("stubTwo", VmProvider.class, () -> new StubVmProvider("stub-two"));

    private static final String UTM_DIRECTORY = "vm.utm.directory=/vms";
    private static final String UTM_CLI = "vm.utm.cli=/utmctl";

    @Test
    void qemuOnlyConfigurationStartsWithoutAnyVmUtmProperties() {
        runner.withPropertyValues("vm.providers=qemu")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(UtmVmProvider.class).size());
                    assertEquals(
                            "qemu",
                            context.getBean(VmProviderRegistry.class).enabledProviders().getFirst().providerId()
                    );
                });
    }

    @Test
    void disabledUtmProviderIsNotInstantiatedEvenWhenItsPropertiesAreAbsent() {
        runner.withPropertyValues("vm.providers=qemu")
                .run(context -> assertEquals(0, context.getBeansOfType(UtmVmProvider.class).size()));
    }

    @Test
    void enabledUtmWithoutItsPropertiesFailsWithAClearMessage() {
        runner.withPropertyValues("vm.providers=utm")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootCauseMessage(context.getStartupFailure())
                            .contains("vm.utm.directory and vm.utm.cli must both be configured"));
                });
    }

    @Test
    void defaultsToUtmWhenNeitherPropertyIsPresent() {
        runner.withPropertyValues(UTM_DIRECTORY, UTM_CLI)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(
                            "utm",
                            context.getBean(VmProviderRegistry.class).enabledProviders().getFirst().providerId()
                    );
                });
    }

    @Test
    void legacyVmProviderStillEnablesUtmWithUnchangedSettings() {
        runner.withPropertyValues(
                        "vm.provider=utm",
                        UTM_DIRECTORY,
                        UTM_CLI
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(UtmVmProvider.class).size());
                    assertEquals(
                            "utm",
                            context.getBean(VmProviderRegistry.class).enabledProviders().getFirst().providerId()
                    );
                });
    }

    @Test
    void legacyPackageSuffixDefaultsToUtmExtension() {
        runner.withPropertyValues("vm.provider=utm", UTM_DIRECTORY, UTM_CLI)
                .run(context -> {
                    String suffix = context.getBean(UtmVmProvider.class)
                            .configuration().stream()
                            .filter(entry -> entry.key().equals("packageSuffix"))
                            .findFirst()
                            .orElseThrow()
                            .value();

                    assertEquals(".utm", suffix);
                });
    }

    @Test
    void vmProvidersTakesPrecedenceOverLegacyProperty() {
        // vm.provider=utm would require UTM settings; vm.providers=qemu must win.
        runner.withPropertyValues("vm.providers=qemu", "vm.provider=utm")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(UtmVmProvider.class).size());
                    assertEquals(
                            "qemu",
                            context.getBean(VmProviderRegistry.class).enabledProviders().getFirst().providerId()
                    );
                });
    }

    @Test
    void configuredOrderAndWhitespaceArePreserved() {
        runner.withPropertyValues("vm.providers= qemu , stub-two ")
                .run(context -> assertEquals(
                        java.util.List.of("qemu", "stub-two"),
                        context.getBean(VmProviderRegistry.class).enabledProviders()
                                .stream()
                                .map(VmProvider::providerId)
                                .toList()
                ));
    }

    @Test
    void currentSucceedsWithExactlyOneEnabledProvider() {
        runner.withPropertyValues("vm.providers=qemu")
                .run(context -> assertEquals(
                        "qemu",
                        context.getBean(VmProviderRegistry.class).enabledProviders().getFirst().providerId()
                ));
    }

    @Test
    void multipleEnabledProvidersAreAvailableInConfiguredOrder() {
        runner.withPropertyValues("vm.providers=qemu,stub-two")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(
                            java.util.List.of("qemu", "stub-two"),
                            context.getBean(VmProviderRegistry.class).enabledProviders()
                                    .stream()
                                    .map(VmProvider::providerId)
                                    .toList()
                    );
                });
    }

    @Test
    void unknownConfiguredProviderIdFailsAtStartup() {
        runner.withPropertyValues("vm.providers=freebsd")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootCauseMessage(context.getStartupFailure())
                            .contains("'freebsd' has no implementation"));
                });
    }

    @Test
    void invalidConfiguredProviderIdFailsAtStartup() {
        runner.withPropertyValues("vm.providers=qemu_x")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootCauseMessage(context.getStartupFailure())
                            .contains("Invalid provider id 'qemu_x'"));
                });
    }

    @Test
    void duplicateImplementationsFailAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProviderConfiguration.class)
                .withBean("first", VmProvider.class, () -> new StubVmProvider("qemu"))
                .withBean("second", VmProvider.class, () -> new StubVmProvider("qemu"))
                .withPropertyValues("vm.providers=qemu")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootCauseMessage(context.getStartupFailure())
                            .contains("Duplicate VM provider implementations for id 'qemu'"));
                });
    }

    @Test
    void utmProviderIdMatchesConditionAnnotation() {
        ConditionalOnVmProvider annotation =
                UtmVmProvider.class.getAnnotation(ConditionalOnVmProvider.class);

        assertEquals("utm", annotation.value());
    }

    @Test
    void testProfileNeverInstantiatesRealProviders() {
        // The test provider reports the id "utm", so this configuration exercises the
        // test stand-in and never creates UtmVmProvider.
        new ApplicationContextRunner()
                .withUserConfiguration(TestProfileConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("vm.providers=utm", UTM_DIRECTORY, UTM_CLI)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(UtmVmProvider.class).size());
                    assertEquals(1, context.getBeansOfType(TestVmProvider.class).size());
                    assertEquals(
                            "UTM (test)",
                            context.getBean(VmProviderRegistry.class).enabledProviders()
                                    .getFirst().displayName()
                    );
                });
    }

    @Test
    void testProfileDoesNotActivateRealProviderIdsOtherThanTheTestStandIn() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestProfileConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("vm.providers=qemu")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootCauseMessage(context.getStartupFailure())
                            .contains("'qemu' has no implementation"));
                });
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable cause = failure;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return String.valueOf(cause.getMessage());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VmProperties.class)
    @Import({UtmProviderConfiguration.class, UtmVmProvider.class, VmProviderRegistry.class})
    static class ProviderConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VmProperties.class)
    @Import({UtmProviderConfiguration.class, UtmVmProvider.class, VmProviderRegistry.class,
            de.fjordkommission.utmbackup.provider.TestVmProvider.class})
    static class TestProfileConfiguration {
    }
}
