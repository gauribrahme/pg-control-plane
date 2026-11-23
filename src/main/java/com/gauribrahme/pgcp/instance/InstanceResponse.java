package com.gauribrahme.pgcp.instance;

import java.time.Instant;

public record InstanceResponse(String id, String tenantId, String name, Plan plan, int cpu, int memoryGb,
                               int storageGb, String pgVersion, InstanceState state, String endpoint,
                               boolean driftDetected, String failureReason, long version,
                               Instant createdAt, Instant updatedAt) {
    public static InstanceResponse from(Instance i) {
        return new InstanceResponse(i.getId(), i.getTenantId(), i.getName(), i.getPlan(), i.getPlan().cpu(),
                i.getPlan().memoryGb(), i.getStorageGb(), i.getPgVersion(), i.getState(), i.getEndpoint(),
                i.isDriftDetected(), i.getFailureReason(), i.getVersion(), i.getCreatedAt(), i.getUpdatedAt());
    }
}
