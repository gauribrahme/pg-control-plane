package com.gauribrahme.pgcp.instance;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * The desired-state record for one PostgreSQL instance. plan/storageGb are the DESIRED values;
 * what actually exists is asked from the provisioner (see Reconciler).
 */
@Entity
@Table(name = "instance")
public class Instance {
    @Id
    private String id;
    private String tenantId;
    private String name;
    @Enumerated(EnumType.STRING)
    private Plan plan;
    private int storageGb;
    private String pgVersion;
    @Enumerated(EnumType.STRING)
    private InstanceState state;
    private String endpoint;
    private String failureReason;
    private boolean driftDetected;
    @Version
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Instance() {}

    public Instance(String tenantId, String name, Plan plan, int storageGb, String pgVersion, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.name = name;
        this.plan = plan;
        this.storageGb = storageGb;
        this.pgVersion = pgVersion;
        this.state = InstanceState.REQUESTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The only way to change state: illegal moves throw, so no code path can skip the state machine. */
    public void transitionTo(InstanceState next, Instant now) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateTransitionException(state, next);
        }
        this.state = next;
        this.updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        transitionTo(InstanceState.FAILED, now);
        this.failureReason = reason;
    }

    public void setDesired(Plan plan, int storageGb, Instant now) {
        this.plan = plan;
        this.storageGb = storageGb;
        this.updatedAt = now;
    }

    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setDriftDetected(boolean driftDetected, Instant now) {
        this.driftDetected = driftDetected;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public Plan getPlan() { return plan; }
    public int getStorageGb() { return storageGb; }
    public String getPgVersion() { return pgVersion; }
    public InstanceState getState() { return state; }
    public String getEndpoint() { return endpoint; }
    public String getFailureReason() { return failureReason; }
    public boolean isDriftDetected() { return driftDetected; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
