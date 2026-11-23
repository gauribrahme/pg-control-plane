# Notes (decisions and open items)

## Decisions
- Spring Boot 3.3.5, Java 17, Maven wrapper 3.9.9 committed. H2 (PostgreSQL mode) for tests with the same Flyway migrations.
- Worker and reconciler are driven by `pollOnce()` / `reconcileOnce()` in tests; scheduling is disabled in the test profile (`pgcp.scheduling.enabled=false`).
- Added two transitions beyond the brief: `AVAILABLE -> FAILED` (reconciler: resource missing) and `FAILED -> DELETING` (cleanup).
- Quota violations return 422, state conflicts 409, unknown or foreign-tenant ids 404.
- Validation problem responses do not set a custom `type` URI (Spring ignored it on method-validation errors); other domain errors use `urn:pgcp:problem:<code>`.

## Kubernetes adapter testing
`KubernetesProvisionerTest` uses fabric8's in-process mock server (CRUD mode). It works without Docker and passes. It verifies object creation,
idempotent re-create, describe, resize, backup Job creation and delete. It cannot verify pod scheduling, readiness, PVC expansion or real RBAC.

## Not verified on this machine
- `PostgresContainerTest` (real PostgreSQL + `FOR UPDATE SKIP LOCKED`): skipped, Docker daemon not running.
- Docker image build, docker-compose stack, Grafana dashboard rendering, Kubernetes manifests (`kubectl apply`): never run.
- Terraform: no binary available, so `terraform fmt`, `validate` and `plan` were never run. Files are hand-formatted; CI runs `fmt -check` and `validate` and may flag whitespace.

## Open items
- Name uniqueness per tenant is a code check, not a DB constraint.
- Retry jitter, error classification (transient vs permanent), per-instance serialisation of work items.
- Await backup Job completion; ship backups to Object Storage.
- Authentication; derive tenant from token.
