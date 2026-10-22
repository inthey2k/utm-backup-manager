package de.fjordkommission.utmbackup.config;

import java.util.Properties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuildInfoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BuildProperties.class)
    BuildProperties buildProperties() {
        Properties properties = new Properties();
        properties.setProperty("version", "development");
        return new BuildProperties(properties);
    }
}