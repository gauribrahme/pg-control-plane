package com.gauribrahme.pgcp.tenant;

import com.gauribrahme.pgcp.config.PgcpProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {
    private final TenantRepository tenants;
    private final PgcpProperties props;
    private final Clock clock;

    public TenantService(TenantRepository tenants, PgcpProperties props, Clock clock) {
        this.tenants = tenants;
        this.props = props;
        this.clock = clock;
    }

    /** Tenants are created lazily on first use with the default quota; locks the row for the caller's transaction. */
    @Transactional
    public Tenant lockOrCreate(String tenantId) {
        return tenants.findLockedById(tenantId).orElseGet(() ->
                tenants.saveAndFlush(new Tenant(tenantId, props.quota().defaultMaxInstances(), Instant.now(clock))));
    }
}
