# GCP synthetic E2E fixtures

> **Location note:** the normalized replay array (called `normalized-events.json`
> below) now lives at [`../replay/gcp-events.json`](../replay/gcp-events.json) so
> `FixtureReplaySeeder` can select it by provider filename prefix. The native
> Cloud Audit Log fixture stays in this directory.

Documentation-accurate GCP provider inputs plus the normalized events that
drive the offline (no-credentials) E2E harness for the Discovery → Catalog
spine.

## Files

| File | What it is |
|---|---|
| `normalized-events.json` | JSON array of 9 `IngestRequestDTO` (flat camelCase) fed to `ILogIngestService.ingest()`. These are the **normalized** events the offline harness replays - the shape every provider adapter emits after normalization. |
| `cloud-audit-sample.json` | **Native** GCP Cloud Audit Log fixture (raw provider format, before normalization). Shows the upstream shape the GCP adapter reads and the `rawEvent` embedded in the normalized events mirrors. |

## Real GCP format mirrored

`cloud-audit-sample.json` mirrors the **GCP Cloud Audit Logs `AuditLog`
`protoPayload`** (`type.googleapis.com/google.cloud.audit.AuditLog`), the same
message written to the `cloudaudit.googleapis.com` log streams (Admin Activity
and Data Access). Each entry carries the fields the adapter maps:

- `protoPayload.methodName`: the API method (e.g. `storage.buckets.create`).
- `protoPayload.resourceName`: the resource the call targeted.
- `protoPayload.serviceName`: the GCP service (`storage.googleapis.com`,
  `bigquery.googleapis.com`).
- `protoPayload.authenticationInfo.principalEmail`: the caller identity.
- `protoPayload.requestMetadata.callerIp`: caller IP.
- `resource.type` + `timestamp` - Logging envelope classification and time.

This is the GCP analogue of the sibling fixtures:

- AWS - **AWS CloudTrail record** (`e2e-tests/fixtures/aws/cloudtrail-sample.json`).
- Azure - **Azure Activity Log `eventProperties`**.

Every `normalized-events.json` entry embeds a small native `protoPayload`
snippet under `rawEvent` so the normalized event stays traceable back to the
Cloud Audit Log shape it was derived from.

## Derivation contract (asset URI → catalog columns)

da-catalog derives `cloud_provider` and `service_type` **from the asset URI**
(`targetResourceId`) and resource kind, per
`DiscoveryAssetDiscoveredListener.deriveCloudProvider` /
`deriveServiceType`. The URIs below are chosen so the derivation is
deterministic and lands on CHECK-valid values:

| `targetResourceId` (asset URI) | `cloud_provider` | `service_type` |
|---|---|---|
| `gs://dlap-gcp-e2e-analytics` | GCP | GCS_BUCKET |
| `gs://dlap-gcp-e2e-raw-events` | GCP | GCS_BUCKET |
| `gs://dlap-gcp-e2e-archive` | GCP | GCS_BUCKET |
| `projects/dlap-gcp-e2e/datasets/analytics/tables/orders` | GCP | BIGQUERY_TABLE |
| `projects/dlap-gcp-e2e/datasets/marketing/tables/campaign_events` | GCP | BIGQUERY_TABLE |

Notes on the derivation:

- **`cloud_provider`**: `sourceProvider="GCP"` maps directly to `GCP`. The URI
  fallback also holds: `projects/...` URIs start with `projects/` (→ GCP), and
  the `gs://` buckets carry `targetResourceKind` = `storage.googleapis.com/Bucket`.
- **`service_type`**: `gs://` buckets derive `GCS_BUCKET` via the resource kind
  (`storage.googleapis.com/Bucket` contains `bucket`); `.../tables/...` URIs
  derive `BIGQUERY_TABLE` via the `/tables/` path segment.

## Event mix (9 events)

- 5 × `CREATE` - 5 **distinct** assets → 5 distinct catalog rows
  (3 GCS buckets, 2 BigQuery tables).
- 1 × `ACCESS` - read on `gs://dlap-gcp-e2e-analytics`.
- 1 × `UPDATE` - schema change on the `orders` table.
- 1 × `PERMISSION` - `storage.buckets.setIamPolicy` on `gs://dlap-gcp-e2e-raw-events`.
- 1 × `DELETE` - `storage.buckets.delete` on `gs://dlap-gcp-e2e-archive`.

Every event has a unique `requestId` (`gcp-e2e-0001` … `gcp-e2e-0009`).
