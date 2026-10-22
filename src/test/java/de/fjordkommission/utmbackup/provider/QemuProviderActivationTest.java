package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.provider.qemu.QemuProperties;
import de.fjordkommission.utmbackup.provider.qemu.QemuProviderConfiguration;
import de.fjordkommission.utmbackup.provider.qemu.QemuVmProvider;
import de.fjordkommission.utmbackup.provider.utm.UtmProperties;
import de.fjordkommission.utmbackup.provider.utm.UtmProviderConfiguration;
import de.fjordkommission.utmbackup.provider.utm.UtmVmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QEMU and UTM activation next to each other. Each provider registers its own settings
 * through its configuration class, so this context has no central knowledge of them.
 */
class QemuProviderActivationTest {

    private static final String UTM_SETTINGS = "vm.utm.directory=/vms/utm";
    private static final String UTM_CLI = "vm.utm.cli=/utmctl";
    private static final String QEMU_DIRECTORY = "vm.qemu.directory=/vms/qemu";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProviderConfiguration.class);

    @Test
    void utmAndQemuCanBeEnabledTogether() {
        runner.withPropertyValues("vm.providers=utm,qemu", UTM_SETTINGS, UTM_CLI, QEMU_DIRECTORY)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(UtmVmProvider.class).size());
                    assertEquals(1, context.getBeansOfType(QemuVmProvider.class).size());
                    assertEquals(
                            List.of("utm", "qemu"),
                            context.getBean(VmProviderRegistry.class).enabledProviders().stream()
                                    .map(VmProvider::providerId)
                                    .toList()
                    );
                });
    }

    @Test
    void qemuSettingsAreRegisteredAndBoundWhenQemuIsEnabled() {
        runner.withPropertyValues("vm.providers=utm,qemu", UTM_SETTINGS, UTM_CLI, QEMU_DIRECTORY)
                .run(context -> {
                    assertEquals(
                            Path.of("/vms/qemu"),
                            context.getBean(QemuProperties.class).directory()
                    );
                    assertEquals(
                            Path.of("/vms/utm"),
                            context.getBean(UtmProperties.class).directory()
                    );
                });
    }

    @Test
    void qemuIsNotCreatedAndItsSettingsAreNotRegisteredWhenItIsNotEnabled() {
        runner.withPropertyValues("vm.providers=utm", UTM_SETTINGS, UTM_CLI, QEMU_DIRECTORY)
                .run(context -> {
                    assertEquals(0, context.getBeansOfType(QemuVmProvider.class).size());
                    assertEquals(0, context.getBeansOfType(QemuProperties.class).size());
                    assertEquals(1, context.getBeansOfType(UtmProperties.class).size());
                });
    }

    @Test
    void qemuOnlyConfigurationDoesNotRequireOrCreateUtm() {
        runner.withPropertyValues("vm.providers=qemu", QEMU_DIRECTORY)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(UtmVmProvider.class).size());
                    assertEquals(0, context.getBeansOfType(UtmProperties.class).size());
                    assertEquals(1, context.getBeansOfType(QemuProperties.class).size());
                });
    }

    @Test
    void missingQemuDirectoryDoesNotPreventStartupOrUtm() {
        runner.withPropertyValues("vm.providers=utm,qemu", UTM_SETTINGS, UTM_CLI)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(UtmVmProvider.class).size());

                    QemuVmProvider qemu = context.getBean(QemuVmProvider.class);

                    IllegalStateException error = assertThrows(
                            IllegalStateException.class,
                            qemu::findAll
                    );
                    assertTrue(error.getMessage().contains("vm.qemu.directory"), error.getMessage());
                });
    }

    /**
     * Imports the provider configuration classes exactly as component scanning does in
     * the application, so the test exercises their registration rather than a copy of it.
     */
    @Configuration
    @EnableConfigurationProperties(VmProperties.class)
    @Import({
            UtmProviderConfiguration.class,
            QemuProviderConfiguration.class,
            UtmVmProvider.class,
            QemuVmProvider.class,
            VmProviderRegistry.class
    })
    static class ProviderConfiguration {
    }
}
