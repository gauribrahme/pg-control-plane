package com.gauribrahme.pgcp.provisioner;

import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.Plan;

public record ProvisionSpec(String tenantId, String instanceId, String name, Plan plan, int storageGb,
                            String pgVersion) {
    public static ProvisionSpec from(Instance i) {
        return new ProvisionSpec(i.getTenantId(), i.getId(), i.getName(), i.getPlan(), i.getStorageGb(),
                i.getPgVersion());
    }
}
