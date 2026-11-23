package com.gauribrahme.pgcp.provisioner;

import java.util.Optional;

/**
 * Port to whatever actually runs PostgreSQL (Kubernetes, a cloud API, an in-memory fake).
 * Every mutating method MUST be idempotent: the workflow worker is at-least-once, so any call can be repeated
 * after a crash or timeout and must converge to the same result.
 */
public interface DatabaseProvisioner {

    /** Create the database if it does not exist; if it already exists, return its current description. */
    ProvisionResult create(ProvisionSpec spec);

    /** Actual state, or empty if the resource does not exist. Read-only; used by the reconciler. */
    Optional<ActualState> describe(String tenantId, String instanceId);

    /** Make the actual compute/storage match the spec (grow only for storage). */
    void resize(ProvisionSpec spec);

    /** Start a backup and return its location. Repeating the same backupId must not create a second backup. */
    String backup(ProvisionSpec spec, String backupId);

    /** Remove the database; succeeds if it is already gone. */
    void delete(String tenantId, String instanceId);
}
