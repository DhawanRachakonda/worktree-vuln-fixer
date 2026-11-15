package dev.subscriber.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the settings records. It is a separate configuration class (not an annotation on the application class) so that
 * database-only test slices do not demand every setting.
 */
@Configuration(proxyBeanMethods = false)
@ConfigurationPropertiesScan(basePackageClasses = SubscriberProperties.class)
public class PropertiesConfiguration {
}
