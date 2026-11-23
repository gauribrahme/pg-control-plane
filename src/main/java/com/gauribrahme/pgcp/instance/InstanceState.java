package com.gauribrahme.pgcp.instance;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle states and the explicit transition table.
 * Beyond the brief: AVAILABLE -> FAILED (reconciler found the resource missing) and
 * FAILED -> DELETING (so a failed instance can still be cleaned up).
 */
public enum InstanceState {
    REQUESTED, PROVISIONING, AVAILABLE, UPDATING, DELETING, DELETED, FAILED;

    private static final Map<InstanceState, Set<InstanceState>> ALLOWED = new EnumMap<>(InstanceState.class);

    static {
        ALLOWED.put(REQUESTED, EnumSet.of(PROVISIONING, FAILED));
        ALLOWED.put(PROVISIONING, EnumSet.of(AVAILABLE, FAILED));
        ALLOWED.put(AVAILABLE, EnumSet.of(UPDATING, DELETING, FAILED));
        ALLOWED.put(UPDATING, EnumSet.of(AVAILABLE, FAILED));
        ALLOWED.put(DELETING, EnumSet.of(DELETED, FAILED));
        ALLOWED.put(FAILED, EnumSet.of(DELETING));
        ALLOWED.put(DELETED, EnumSet.noneOf(InstanceState.class));
    }

    public boolean canTransitionTo(InstanceState next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<InstanceState> allowedNext() {
        return Set.copyOf(ALLOWED.get(this));
    }

    /** States where a workflow is (or should be) running; a permanent workflow failure moves these to FAILED. */
    public boolean isInProgress() {
        return this == REQUESTED || this == PROVISIONING || this == UPDATING || this == DELETING;
    }
}
