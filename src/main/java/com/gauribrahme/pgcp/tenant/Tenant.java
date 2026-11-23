package com.gauribrahme.pgcp.tenant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tenant")
public class Tenant {
    @Id
    private String id;
    private int maxInstances;
    private Instant createdAt;

    protected Tenant() {}

    public Tenant(String id, int maxInstances, Instant now) {
        this.id = id;
        this.maxInstances = maxInstances;
        this.createdAt = now;
    }

    public String getId() { return id; }
    public int getMaxInstances() { return maxInstances; }
    public void setMaxInstances(int maxInstances) { this.maxInstances = maxInstances; }
}
