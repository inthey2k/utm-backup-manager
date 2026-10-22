package de.fjordkommission.utmbackup.provider.qemu;

import de.fjordkommission.utmbackup.provider.ConditionalOnVmProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the QEMU provider settings. Active only when QEMU is enabled in
 * {@code vm.providers}, so a disabled QEMU never binds {@code vm.qemu.*}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnVmProvider("qemu")
@EnableConfigurationProperties(QemuProperties.class)
public class QemuProviderConfiguration {
}
