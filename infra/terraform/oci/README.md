# OCI infrastructure (Terraform)

Personal portfolio code. It has been formatted and structured to `terraform fmt` conventions but was
**never applied** against a real OCI tenancy (no account was available). Treat it as a reviewed draft.

```
modules/vcn           VCN, internet + NAT gateways, route tables, security lists, 3 subnets
modules/oke           OKE BASIC cluster + one node pool
environments/dev      Root module that wires the two together and configures remote state
```

## Free-tier notes
- OKE `BASIC_CLUSTER` has no control-plane fee; you pay only for worker nodes and load balancers.
- `VM.Standard.A1.Flex` (Arm) is Always Free eligible up to 4 OCPU / 24 GB total. The defaults use 2 nodes x 2 OCPU x 12 GB.
  Arm shapes need an aarch64 node image. Limits change; check the current Always Free page.
- A flexible load balancer (10 Mbps) is also Always Free, but only one.

## State
Terraform state is the source of truth mapping config to real resources. It can contain secrets, so it
is never committed (`*.tfstate` is git-ignored) and lives in an Object Storage bucket instead.
The `dev` root uses the `s3` backend against OCI's S3-compatible endpoint. Create the bucket first (with
versioning enabled so a bad apply can be rolled back), create a Customer Secret Key for a dedicated
IAM user, export it as `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, copy `backend.hcl.example` to
`backend.hcl`, then `terraform init -backend-config=backend.hcl`.

## Locking
The S3 backend's native lock file (`use_lockfile`, Terraform 1.10+) or a DynamoDB table does not exist on OCI.
Be honest about that: with this backend, concurrent applies are not reliably prevented. Options are the
native OCI Object Storage backend in newer Terraform releases, Terraform Cloud or OCI Resource Manager
(which locks stacks), or serialising applies through a single CI job with a concurrency group.

## Drift
`terraform plan` compares state with the real world. Run it on a schedule in CI (read-only credentials)
and alert on a non-empty plan. Fix by applying, or by `terraform import` if a resource was created by hand.

## Module versioning
Here modules are local paths for simplicity. For real use, publish them in a Git repo and pin by tag:
`source = "git::https://example.com/org/tf-oci-vcn.git?ref=v1.2.0"`. Follow semver: new optional
variables are minor, renamed variables or resources (which force replacement) are major. Pin the provider
with `~> 6.0` and commit `.terraform.lock.hcl` so every machine resolves the same provider build.
