package com.gauribrahme.pgcp.instance;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "backup")
public class Backup {
    public enum Status { REQUESTED, COMPLETED, FAILED }

    @Id
    private String id;
    private String instanceId;
    private String tenantId;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String location;
    private Instant createdAt;
    private Instant updatedAt;

    protected Backup() {}

    public Backup(String instanceId, String tenantId, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = instanceId;
        this.tenantId = tenantId;
        this.status = Status.REQUESTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void complete(String location, Instant now) {
        this.status = Status.COMPLETED;
        this.location = location;
        this.updatedAt = now;
    }

    public void fail(Instant now) {
        this.status = Status.FAILED;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public Status getStatus() { return status; }
    public String getLocation() { return location; }
    public Instant getCreatedAt() { return createdAt; }
}
