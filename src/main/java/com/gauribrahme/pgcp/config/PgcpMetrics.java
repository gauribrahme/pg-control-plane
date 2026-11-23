package com.gauribrahme.pgcp.config;

import com.gauribrahme.pgcp.instance.InstanceRepository;
import com.gauribrahme.pgcp.instance.InstanceState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Custom metrics: provisioning duration, workflow failures, drift, and instances by state. */
@Component
public class PgcpMetrics {
    private final MeterRegistry registry;
    private final Timer provisioningTimer;

    public PgcpMetrics(MeterRegistry registry, InstanceRepository instances) {
        this.registry = registry;
        this.provisioningTimer = Timer.builder("pgcp.provisioning.duration")
                .description("Time from create request to AVAILABLE")
                .publishPercentileHistogram()
                .register(registry);
        for (InstanceState state : InstanceState.values()) {
            // Evaluated at scrape time (one count query per state); fine at this scale, cache it if it ever is not.
            Gauge.builder("pgcp.instances", instances, r -> r.countByState(state))
                    .description("Instances by lifecycle state")
                    .tag("state", state.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
    }

    public void provisioned(Duration d) {
        provisioningTimer.record(d);
    }

    public void workflowFailure(String type, boolean permanent) {
        Counter.builder("pgcp.workflow.failures")
                .description("Failed workflow attempts")
                .tag("type", type)
                .tag("permanent", String.valueOf(permanent))
                .register(registry).increment();
    }

    public void drift(String kind) {
        Counter.builder("pgcp.reconciler.drift")
                .description("Drift detected by the reconciler")
                .tag("kind", kind)
                .register(registry).increment();
    }
}
