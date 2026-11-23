package com.gauribrahme.pgcp.common;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Remembers which instance an Idempotency-Key produced, plus a hash of the request that used it. */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {
    @Id
    private String id;
    private String tenantId;
    private String idemKey;
    private String requestHash;
    private String instanceId;
    private Instant createdAt;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String tenantId, String idemKey, String requestHash, String instanceId, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.instanceId = instanceId;
        this.createdAt = now;
    }

    public String getRequestHash() { return requestHash; }
    public String getInstanceId() { return instanceId; }
}
