package dev.subscriber.security;

import dev.subscriber.config.SubscriberProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Puts the bearer-token filter in front of the read API and the actuator endpoints when a token is configured. */
@Configuration(proxyBeanMethods = false)
public class AccessConfig {

    @Bean
    FilterRegistrationBean<BearerTokenFilter> apiTokenFilter(SubscriberProperties properties) {
        String token = properties.access().token();
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(new BearerTokenFilter(token == null ? "" : token));
        registration.addUrlPatterns("/api", "/api/*", "/actuator", "/actuator/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setEnabled(token != null);
        return registration;
    }
}
