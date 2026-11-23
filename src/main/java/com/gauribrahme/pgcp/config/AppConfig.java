package com.gauribrahme.pgcp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
public class AppConfig {

    /** Injected everywhere instead of Instant.now() so backoff logic is testable. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("PostgreSQL Control Plane API")
                .version("v1")
                .description("Provision and manage PostgreSQL instances per tenant. "
                        + "Tenant is taken from the X-Tenant-Id header. A real system derives it from the "
                        + "authenticated principal (OCI IAM or a JWT claim), never from a client-supplied header."));
    }

    /** Scheduling can be switched off; tests drive the worker and reconciler by hand. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "pgcp.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {}
}
