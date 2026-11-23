# CloudResource: shared richer resource model (ADR-0028)

`CloudResource` (`com.dlap.common.model.entity.CloudResource`, table
`dlap_common.dlap_cloud_resources`) is an **optional** richer model that
complements, not replaces, `Asset`.

## When to use which

| Need | Use |
| --- | --- |
| Canonical, lightweight asset on the auto-flow path (discover → catalog → label → policy → archive/delete) | **`Asset`** (`dlap_assets`) |
| Provider-native detail that doesn't flatten onto `Asset` | **`CloudResource`** (`dlap_cloud_resources`) |

`Asset` remains the single source of truth for the lifecycle pipeline
(ADR-0021). Reach for `CloudResource` only when you need one of the
richer-than-`Asset` capabilities below.

## What `CloudResource` adds over `Asset`

- **Asset-of-assets hierarchy**: `parentResourceId` + `hasChildren`
  model parent/child trees (a BigQuery dataset containing tables, an S3
  bucket containing prefixes/objects, a project containing resources).
  `Asset` is flat.
- **Cross-region replication**: `replicaRegions` records the regions a
  resource is replicated to beyond its primary `region`. `Asset` has a
  single `region`.
- **Large-asset / provider-native metadata**: `providerNativeJson`
  holds the raw provider payload (full describe-output, IAM policy,
  lifecycle rules, …) that is too large or too provider-specific to
  flatten onto `Asset`'s `technical_metadata`.

## Linking the two

`CloudResource.assetId` is a nullable FK to `Asset.assetId`
(`ON DELETE SET NULL`). Typical flow:

1. A scanner discovers a resource and upserts a lightweight `Asset`
   (drives the pipeline).
2. If the resource needs richer detail, the scanner also writes a
   `CloudResource` with `assetId` set to the Asset it complements.
3. Readers that need hierarchy/replication/native payload query
   `ICloudResourceRepository`; everything else uses `IAssetRepository`.

A `CloudResource` may exist without an `Asset` (`assetId = null`) when a
resource has not been promoted to a canonical asset.

## Migration guidance for existing `Asset` callers

You do **not** need to migrate. Existing `Asset` reads/writes are
unchanged. Adopt `CloudResource` incrementally where the three
capabilities above are required:

- **Hierarchy**: today some callers encode parent/child relationships
  inside `Asset.details_json`. Those can move to
  `CloudResource.parentResourceId` for queryable trees.
- **Replication**: callers tracking replica regions in ad-hoc metadata
  can use `replicaRegions`.
- **Native payload**: callers stuffing large provider blobs into
  `Asset.technical_metadata` can move them to
  `providerNativeJson`, keeping `Asset` lean.

## Convergence note

`da-discovery` has its own internal `CloudResource`
(`com.dlap.discovery.common.model.CloudResource`, a JOINED-inheritance
hierarchy). The shared `da-protos` type is the convergence target: the
discovery entity can later collapse onto it, closing the duplication
that ADR-0021/0022 originally flagged - without a flag-day rewrite. Until
then the two coexist (different packages).

## Schema provisioning

`dlap_common` is bootstrapped by raw SQL init scripts, not Flyway
(CLAUDE.md §6). The table ships in both
`deployment/{environments/dev/docker,platform/database}/init-scripts/02-create-common-schema.sql`.
Databases provisioned before 2026-06-13 apply the one-time delta
`deployment/platform/database/upgrades/2026-06-13_add_cloud_resources.sql`.
