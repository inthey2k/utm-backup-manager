package de.fjordkommission.utmbackup.provider.utm;

import de.fjordkommission.utmbackup.provider.ConditionalOnVmProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the UTM provider settings. Active only when UTM is enabled in
 * {@code vm.providers}, so a disabled UTM never binds {@code vm.utm.*}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnVmProvider("utm")
@EnableConfigurationProperties(UtmProperties.class)
public class UtmProviderConfiguration {
}
