package com.gauribrahme.pgcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All tunables in one typed place. See application.yml for the shipped defaults. */
@ConfigurationProperties(prefix = "pgcp")
public record PgcpProperties(
        @DefaultValue Quota quota,
        @DefaultValue Worker worker,
        @DefaultValue Reconciler reconciler,
        @DefaultValue InMemory inMemory) {

    public record Quota(@DefaultValue("5") int defaultMaxInstances) {}

    public record Worker(
            @DefaultValue("10") int batchSize,
            @DefaultValue("1000") long pollIntervalMs,
            @DefaultValue("60") long leaseSeconds,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("2000") long baseBackoffMs,
            @DefaultValue("300000") long maxBackoffMs,
            @DefaultValue("false") boolean skipLocked) {}

    public record Reconciler(@DefaultValue("30000") long intervalMs) {}

    public record InMemory(@DefaultValue("0") long delayMs) {}
}
