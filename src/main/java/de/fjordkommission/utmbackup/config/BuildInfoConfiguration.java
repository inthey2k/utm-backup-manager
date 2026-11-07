package de.fjordkommission.utmbackup.config;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuildInfoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BuildProperties.class)
    BuildProperties buildProperties(
            @Value("${utm-backup-manager.version:development}")
            String version
    ) {
        Properties properties = new Properties();
        properties.setProperty("version", version);
        return new BuildProperties(properties);
    }
}