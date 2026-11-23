# Architecture decisions

Each record: context, decision, consequences. Personal portfolio project; "production" below means "what a real service would need".

## ADR-1: Asynchronous API with a transactional outbox
**Context.** Provisioning a database takes seconds to minutes and can fail halfway. Doing it inside the HTTP request
would hold threads, time out clients and lose work on restart. Writing the DB row and then calling a queue is a dual write
that can leave one without the other.
**Decision.** The API writes the `instance` row and a `work_item` row in one local transaction and returns 202. A worker
reads `work_item` and does the slow work.
**Consequences.** No message broker to run. Work survives restarts. Latency to start work is the poll interval (1 s).
Throughput is bounded by the metadata DB, which is fine for a control plane (low write rate, high value per write).

## ADR-2: Claim work with `FOR UPDATE SKIP LOCKED`, leases and compare-and-set
**Context.** Several control-plane replicas must share one queue without blocking each other or processing an item twice.
**Decision.** Select candidate ids with `FOR UPDATE SKIP LOCKED`, then claim each with an `UPDATE ... WHERE` that only
succeeds if the item is still claimable, setting `locked_until` (a lease) and incrementing `attempts`. Items whose lease expired
are claimable again. H2 (tests) uses the same compare-and-set without the lock clause.
**Consequences.** Delivery is at-least-once, so every step must be idempotent (ADR-4). A poison item that crashes the worker is
bounded because `attempts` counts claims. Lease length must exceed the longest provisioner call.

## ADR-3: Explicit state machine on the entity, plus optimistic locking
**Decision.** `InstanceState` holds the transition table; `Instance.transitionTo` is the only mutator of state and throws
`IllegalStateTransitionException` (HTTP 409). `@Version` detects concurrent writers (the API and worker both touch a row).
Additions beyond the minimum: `AVAILABLE -> FAILED` (reconciler) and `FAILED -> DELETING` (so failures can be cleaned up).
**Consequences.** Bugs that would skip a state fail loudly in tests. Each worker step re-checks the state it expects, so a
repeated step is recognised as already done.

## ADR-5 (numbering note): provisioner calls happen outside DB transactions
Holding a row lock or connection across a remote call that can take minutes exhausts the pool. The worker commits the claim,
calls the provisioner, then commits the state change and the work item completion together. The window between the provisioner
call and the final commit is exactly the crash window ADR-4 covers.

## ADR-4: Idempotency at two layers
**Client to API.** `Idempotency-Key` plus a SHA-256 of the normalised request (name, plan, storage, version after defaults)
stored per tenant with a unique constraint. Same key and hash returns the original instance; same key with a different hash is 409.
A lost race on the unique key is caught and retried once, which then takes the replay path.
**Worker to provisioner.** `create` is get-or-create keyed by instance id; `delete` succeeds if already gone; the Kubernetes
adapter never regenerates an existing password Secret.

## ADR-6: Desired state in the DB, reconciliation against actual state
The reconciler runs periodically over AVAILABLE instances only (workflows own in-flight ones). Missing resource becomes FAILED
and flagged (never auto-recreated: that would hide data loss). Plan or smaller-storage mismatch enqueues a RESIZE. Larger storage is
only flagged because storage cannot shrink. This is level-triggered control, the same idea as a Kubernetes controller.

## ADR-7: Ports and adapters for provisioning
`DatabaseProvisioner` isolates the domain from Kubernetes. The in-memory adapter simulates latency and injectable failures so the retry,
backoff and drift paths are tested deterministically. The Kubernetes adapter uses namespace-per-tenant (blast radius, quota and RBAC
boundaries, NetworkPolicy attachment point), a StatefulSet (stable identity and per-pod PVC), a Secret and a Service.

## ADR-8: Tenant isolation by scoping every query
Repository lookups are `findByIdAndTenantId`; another tenant's id yields 404, not 403, so existence does not leak. The quota check
takes a row lock on the tenant (`PESSIMISTIC_WRITE`) so concurrent creates cannot exceed the limit. The tenant comes from `X-Tenant-Id`
as a demo shortcut; production derives it from the token.

## ADR-9: Portable SQL and H2 for tests
Migrations use only SQL that PostgreSQL and H2 (PostgreSQL mode) both run, so the same Flyway scripts are tested in milliseconds without
Docker. The PostgreSQL-specific `SKIP LOCKED` query is covered by a Testcontainers test that is skipped when Docker is unavailable.
Cost: PostgreSQL-only features (partial indexes, jsonb, `ON CONFLICT`) were avoided.

## ADR-10: Observability
Micrometer meters exposed via Actuator and scraped by Prometheus: provisioning duration (histogram), workflow failures by type and permanence,
instances by state (gauge evaluated at scrape time), drift by kind. MDC carries tenant, instance and request id into every log line.

## Rejected alternatives
- **Kafka/SQS between API and worker:** more infrastructure and still needs the DB write; the outbox is enough at this scale.
- **Calling Kubernetes from the request thread:** ties API latency and availability to the cluster.
- **Sagas/Temporal:** a good fit for longer multi-step flows; overkill for single-step operations here, worth revisiting for multi-step ones (HA setup, upgrades).
