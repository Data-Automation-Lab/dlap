# AZURE synthetic E2E fixtures

> **Location note:** the normalized replay array (called `normalized-events.json`
> below) now lives at [`../replay/azure-events.json`](../replay/azure-events.json)
> so `FixtureReplaySeeder` can select it by provider filename prefix. The native
> Activity Log fixture stays in this directory.

Documentation-accurate AZURE inputs for the offline (credential-free) E2E
harness. Two files, two layers of the spine:

| File | Layer | Fed to |
|---|---|---|
| `normalized-events.json` | NORMALIZED - array of `IngestRequestDTO` | `ILogIngestService.ingest()` (drives the harness) |
| `activity-log-sample.json` | NATIVE - raw Azure Activity Log record | Reference for the future `AzureLogSource` normalizer; not fed directly |

## What each mirrors

The real provider audit-log formats these mirror:

- **AWS**: a *CloudTrail record* (`Records[].userIdentity` / `resources[].ARN`),
  see `../aws/cloudtrail-sample.json`.
- **GCP**: the *Cloud Audit Logs `AuditLog` protoPayload*
  (`methodName` / `resourceName` / `authenticationInfo.principalEmail`),
  see `da-logingest .../source/gcp/GcpLogSource.java`.
- **AZURE**: the *Azure Activity Log `eventProperties`* block
  (`resourceId` as an ARM path, `operationName`, `eventTimestamp`, `caller`,
  `status`, `level`). Mirrored in `activity-log-sample.json`.

`normalized-events.json` is the DLAP-canonical shape - the flat camelCase
JSON mirror of `IngestRequestDTO` (`da-logingest .../dto/IngestRequestDTO.java`),
the same DTO the demo seeder and the AWS/GCP log sources build before calling
`ILogIngestService.ingest()`. The harness posts this array; da-catalog then
derives `cloud_provider` and `service_type` **from the asset URI**
(`targetResourceId`), so the URIs are chosen to derive correctly.

## Events in `normalized-events.json`

8 events: **3 CREATE** (3 distinct assets → 3 distinct catalog rows),
**2 ACCESS**, **1 UPDATE**, **1 PERMISSION**, **1 DELETE**.

- `sourceService`: `Microsoft.Storage` (blob ops) / `Microsoft.Authorization`
  (role assignment).
- Blob write op: `Microsoft.Storage/storageAccounts/blobServices/containers/write`
- Blob delete op: `Microsoft.Storage/storageAccounts/blobServices/containers/delete`
- Permission op: `Microsoft.Authorization/roleAssignments/write`

## Asset URIs → (cloud_provider, service_type)

Derivation rules (da-catalog `DiscoveryAssetDiscoveredListener.deriveCloudProvider`
/ `deriveServiceType`): a URI containing `windows.net` → **AZURE**; containing
`blob.core.windows.net` → **BLOB_STORAGE**. All three CREATE assets use HTTPS
Blob endpoints so they land as `AZURE` + `BLOB_STORAGE` (the `azure://` scheme
is deliberately avoided - it lacks `blob.core.windows.net` and would fall
through to `DATA_LAKE`).

| Asset URI (`targetResourceId`) | cloud_provider | service_type |
|---|---|---|
| `https://dlapazuree2e.blob.core.windows.net/analytics/customers.parquet` | AZURE | BLOB_STORAGE |
| `https://dlapazuree2e.blob.core.windows.net/finance/transactions-2026.parquet` | AZURE | BLOB_STORAGE |
| `https://dlapazuree2e.blob.core.windows.net/marketing/campaign-events.csv` | AZURE | BLOB_STORAGE |

The 3 CREATE events upsert those 3 rows; ACCESS/UPDATE/PERMISSION/DELETE
reference the same three URIs (state transitions, not new rows).

## Lifecycle coverage & the Azure archive caveat

The harness asserts **discovery / label / compliance / delete** for Azure.

**Azure archive currently fails closed** (CP-4, per the lifecycle-action
dispatch convention): `da-autoarchival` has no `AzureCloudStorageService`, so
an archive action against an Azure asset is rejected rather than silently
no-op'd. The archive stage will be asserted for Azure **once
`AzureCloudStorageService` lands and mock-mode is enabled** - until then the
harness treats Azure archive as expected-fail-closed, not a red failure.
