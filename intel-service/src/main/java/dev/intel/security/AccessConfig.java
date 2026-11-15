package dev.intel.security;

import dev.intel.config.IntelProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Puts the bearer-token filter in front of the MCP endpoint and the actuator endpoints when a token is configured (the
 * two orchestrator probes excepted, see {@link BearerTokenFilter}).
 */
@Configuration
public class AccessConfig {

    @Bean
    FilterRegistrationBean<BearerTokenFilter> mcpTokenFilter(IntelProperties properties, Environment environment) {
        String endpoint = environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
        boolean required = properties.access().tokenConfigured();
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(
                new BearerTokenFilter(required ? properties.access().token() : ""));
        registration.addUrlPatterns(endpoint, endpoint + "/*", "/actuator", "/actuator/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setEnabled(required);
        return registration;
    }
}
