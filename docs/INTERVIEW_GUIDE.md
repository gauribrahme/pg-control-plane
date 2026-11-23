# Interview guide

For walking an interviewer through this repo without notes. It is a personal portfolio project (AI-assisted, every change
reviewed and tested); say so plainly. Know what is real (tested logic) and what is a draft (unapplied Terraform, Kubernetes adapter tested
only against a mock API server).

## 60-second pitch
"It is a mini managed-PostgreSQL control plane. A tenant calls a REST API to create, resize, back up or delete a database. The API
records intent and returns 202; a worker does the slow provisioning with retries; a reconciler compares what the DB says should exist with
what the provisioner reports and repairs drift. I designed it around the failure cases: idempotent requests, at-least-once workers, crashes
mid-provision."

## Walk through a create request, file by file
1. `instance/InstanceController.java`: `POST /api/v1/instances`. Header validation (`X-Tenant-Id`, `Idempotency-Key`), `@Valid` body
   (`CreateInstanceRequest`). Returns 202 + `Location`. Errors are shaped by `common/GlobalExceptionHandler` into problem+json.
2. `instance/InstanceService.create`: applies plan defaults, hashes the request (`common/RequestHash`), then in ONE transaction:
   idempotency lookup (`IdempotencyRecord`), `TenantService.lockOrCreate` (row lock), quota check, name check, inserts `Instance(REQUESTED)`,
   `WorkItem(CREATE)` and the idempotency record. A unique-key race is caught and replayed.
3. `workflow/WorkflowService.pollOnce` (scheduled each second): `claim()` runs `WorkItemRepository.findClaimableSkipLocked` then `tryClaim`
   (compare-and-set with a lease and attempts+1).
4. `WorkflowService.create`: REQUESTED to PROVISIONING (small transaction), then `DatabaseProvisioner.create` outside any transaction, then in one
   transaction sets endpoint, PROVISIONING to AVAILABLE, marks the work item DONE and records the duration timer.
5. `provisioner/KubernetesProvisioner.create` (profile k8s): namespace `tenant-<id>`, Secret (random password, kept if it exists), Service, StatefulSet
   `postgres:16` with a PVC template and readiness probe `pg_isready`.
6. Failure path: `WorkflowService.onFailure` stores `last_error`, computes `base * 2^(attempts-1)` capped, reschedules; at max attempts the
   work item and the instance go FAILED with a reason. Metrics in `config/PgcpMetrics`.
7. `workflow/Reconciler` later verifies the result against `describe`.
Tests that prove it: `WorkflowServiceTest`, `InstanceServiceTest`, `ApiFlowTest`.

## Why outbox + SKIP LOCKED
Writing to a queue and the DB separately can leave them inconsistent (DB committed, message lost, or the reverse). Putting the work item in
the same transaction makes "request recorded" and "work scheduled" one atomic fact. `SKIP LOCKED` lets multiple workers pull rows
concurrently: locked rows are skipped instead of waited on, so there is no head-of-line blocking and no duplicate pickup. The lease
(`locked_until`) handles workers that die while holding an item.

## What if the worker crashes mid-provision?
Three crash points, all covered:
- Before the provisioner call: item stays IN_PROGRESS; after the lease expires another worker reclaims it and redoes the step.
- After the provisioner created the database but before the commit: the retry calls `create` again; it is get-or-create, so it returns the existing
  resource (`crashAfterProviderCreateBeforeCommitIsSafeToRerun`).
- After the commit: nothing to do; if only the work item completion were lost, the state guard sees AVAILABLE and marks the item done.
`attempts` increments at claim time, so a crash loop eventually fails the item instead of retrying forever. Test: `workerCrashMidItemIsRecoveredAfterLeaseExpires`.

## How idempotency works
Client key plus request hash stored under a unique (tenant, key). Replay returns the same instance and creates no second work item. Different body
with the same key is 409. Keys are per tenant. Provisioner operations are idempotent by construction (names derive from the instance id).

## Mapping to OCI
- Kubernetes provisioner runs on **OKE**; Terraform in `infra/terraform/oci` builds VCN, subnets and the OKE cluster/node pool.
- Backups should go to **Object Storage** (the pg_dump Job here writes to a PVC as a simplification); Terraform state also lives in Object Storage via the S3-compatible API.
- Tenant identity would come from **IAM** (identity domains, tokens, policies per compartment); the control plane would authorise against compartments instead of a header.
- Secrets would live in **OCI Vault**; images in **OCIR**; metrics could go to **OCI Monitoring** alongside Prometheus; the metadata DB could be OCI PostgreSQL itself.
I have not run any of this on OCI; I would state that and describe the design.

## Scaling to many tenants
Metadata DB: indexes on (tenant, state), work_item claim index; partition or archive finished work items. Workers: scale replicas (SKIP LOCKED), batch size, shard by
tenant hash if one queue becomes hot. Reconciler: page and shard instead of scanning all; prefer watch/event-driven triggers plus a slow full resync. Provisioning: per-tenant concurrency
limits so one tenant cannot starve others; multiple clusters with a placement service. Quotas: add CPU/storage totals and rate limits.

## 30 likely questions

### Java and Spring
1. **Why does the controller return 202 and not 201?** The resource is accepted but not created yet; 202 plus `Location` tells the client where to poll.
2. **How do transactions work in the service, and why `TransactionTemplate` instead of `@Transactional`?** I need to catch a constraint violation and rerun the whole transaction;
   a `@Transactional` method cannot retry itself (self-invocation bypasses the proxy and the exception rolls back the outer). Programmatic transactions make the boundary explicit.
3. **What does `@Version` do?** Hibernate adds `WHERE version = ?` to updates; a concurrent write makes zero rows match and throws `OptimisticLockingFailureException`, mapped to 409.
4. **Why constructor injection and records?** Immutability, testability without Spring, and `@ConfigurationProperties` records give typed config with defaults.
5. **How are errors turned into problem+json?** A `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`; domain errors are `ApiException` with status and code; validation failures add an `errors` array.
6. **How do you test the web layer vs the full stack?** `@WebMvcTest` with a mocked service for status codes and shapes; `@SpringBootTest` on H2 for behaviour; a Clock bean replaced by `MutableClock` so backoff is tested without sleeping.
7. **What are the risks of `@Scheduled` workers?** Overlap across replicas (solved by claiming), long tasks delaying the next poll (`fixedDelay`, not `fixedRate`), and swallowed exceptions (caught and logged).

### PostgreSQL
8. **Explain `FOR UPDATE SKIP LOCKED`.** Row-locks matching rows, silently skipping rows locked by other transactions; ideal for work queues.
9. **Why then also a compare-and-set update?** The lock only lasts for the transaction; I commit the claim and then work for minutes. The CAS plus lease records ownership durably and works on H2.
10. **How does the quota check avoid races?** `SELECT ... FOR UPDATE` on the tenant row serialises creates per tenant. Without it two concurrent requests could both see count 4 and make instances 5 and 6.
11. **Why `timestamp with time zone`?** Instants are absolute; avoids session timezone bugs. Both PostgreSQL and H2 support it.
12. **How would you evolve the schema safely?** Flyway versioned migrations, expand/contract (add nullable column, backfill, switch code, then drop), never edit applied migrations.
13. **Unique constraints versus application checks?** Idempotency uses a real unique constraint. Name uniqueness is only checked in code (known limitation); a partial unique index on non-deleted rows would fix it on PostgreSQL.
14. **What would you index and why?** `work_item(status, next_attempt_at)` for claiming; `instance(tenant_id, state)` for listing and quota counts.

### Distributed systems
15. **At-least-once vs exactly-once?** Exactly-once is not achievable across the DB and the provisioner; I take at-least-once delivery and make operations idempotent so repeats are harmless.
16. **How does retry/backoff work and why exponential?** `base * 2^(n-1)` capped at a max; spreads load and gives a failing dependency time to recover. Adding jitter would avoid synchronised retries (not implemented).
17. **Why a lease rather than a lock?** A dead worker cannot release a lock; a lease expires on its own.
18. **What is the difference between a retry and a permanent failure?** I do not classify errors: everything retries until max attempts. A real system would distinguish validation errors (fail fast) from transient ones.
19. **What is the reconciler's relation to the worker?** The worker is edge-triggered (does what was requested); the reconciler is level-triggered (converges whatever happened). Both are needed.
20. **How do you avoid two workers acting on the same instance?** Different work items for one instance can run concurrently in principle; state guards and `@Version` make conflicting writes fail and retry. Per-instance serialisation would be a next step.

### Kubernetes
21. **Why a StatefulSet for PostgreSQL?** Stable pod identity and per-pod PVCs that survive rescheduling; a Deployment would not keep storage tied to the pod.
22. **Why namespace per tenant?** Isolation of names and RBAC, quotas (ResourceQuota), network policies, and simple cleanup. Cost: many namespaces; alternative is label-based isolation in shared namespaces.
23. **How does the control plane get permissions?** A ServiceAccount with a ClusterRole limited to the resources it manages (`deploy/k8s/rbac.yaml`); cluster scope is needed because it creates namespaces.
24. **How would you resize storage?** `volumeClaimTemplates` are immutable, so patch the existing PVC (needs a StorageClass with `allowVolumeExpansion`); the adapter does that and records the size in an annotation.
25. **What is missing for production PostgreSQL on Kubernetes?** HA (an operator such as CloudNativePG or Patroni), WAL archiving and PITR, PodDisruptionBudgets, anti-affinity, TLS, connection pooling.

### Terraform and operations
26. **What is Terraform state and why remote?** The mapping between config and real resources; remote storage allows sharing, and versioning allows recovery. It may hold secrets, so it is not in Git.
27. **How is state locking handled here?** Honestly, it is an open question for OCI's S3-compatible backend; options are Resource Manager, Terraform Cloud, or serialised CI applies (see the Terraform README).
28. **How do you detect drift?** Scheduled `terraform plan` with read-only credentials and alert on diffs; fix by applying or `terraform import`.
29. **How do you version modules?** Git tags with semver and `?ref=` pins; provider constraints with a committed lock file.
30. **What would you do on-call when provisioning latency spikes?** Check `pgcp_provisioning_duration` and `pgcp_workflow_failures_total` by type, look at `work_item.last_error` and attempts, check lease expiries (crashes), then the cluster (pending pods, storage class), then the metadata DB (locks, pool).

## Things to be ready to admit
No auth, no HA, unapplied Terraform, mock-only Kubernetes test, backup not awaited, `SKIP LOCKED` SQL tested only when Docker is available.
