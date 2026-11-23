package com.gauribrahme.pgcp.workflow;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Outbox row: one unit of asynchronous work for an instance, written in the same transaction as the instance change. */
@Entity
@Table(name = "work_item")
public class WorkItem {
    @Id
    private String id;
    private String instanceId;
    @Enumerated(EnumType.STRING)
    private WorkType type;
    private String payload;
    @Enumerated(EnumType.STRING)
    private WorkStatus status;
    private int attempts;
    private int maxAttempts;
    private Instant nextAttemptAt;
    private String lockedBy;
    private Instant lockedUntil;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;

    protected WorkItem() {}

    public WorkItem(String instanceId, WorkType type, String payload, int maxAttempts, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = instanceId;
        this.type = type;
        this.payload = payload;
        this.status = WorkStatus.PENDING;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markDone(Instant now) {
        this.status = WorkStatus.DONE;
        release(now);
    }

    public void scheduleRetry(String error, Instant retryAt, Instant now) {
        this.status = WorkStatus.PENDING;
        this.lastError = error;
        this.nextAttemptAt = retryAt;
        release(now);
    }

    public void markFailed(String error, Instant now) {
        this.status = WorkStatus.FAILED;
        this.lastError = error;
        release(now);
    }

    private void release(Instant now) {
        this.lockedBy = null;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public WorkType getType() { return type; }
    public String getPayload() { return payload; }
    public WorkStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getLastError() { return lastError; }
}
