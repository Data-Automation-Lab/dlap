# Normalized replay fixtures (cross-cloud offline E2E)

Each file here is a **JSON array of `IngestRequestDTO`** (the flat, camelCase,
DLAP-canonical event shape) for one cloud. `FixtureReplaySeeder` in
`da-logingest` replays them through `ILogIngestService.ingest()` in-process (no
cloud credentials, no auth token), driving the real spine:

```
<cloud>-events.json
   → FixtureReplaySeeder → ILogIngestService.ingest → DlapLogEvent
   → dlap.log.event.normalized → DiscoveryLogEventProcessor (router)
   → typed dlap.discovery.asset.* topics
   → da-catalog DiscoveryAssetDiscoveredListener → dlp_assets upsert
```

| File | Cloud | CREATE events | `dlap_assets` rows (`asset_uri`, provider, service type) |
|---|---|---|---|
| `aws-events.json` | AWS | 3 × `s3://dlap-aws-e2e-*` | `s3://dlap-aws-e2e-*`, `AWS`, `S3_BUCKET` |
| `gcp-events.json` | GCP | 3 × `gs://dlap-gcp-e2e-*`, 2 × `projects/dlap-gcp-e2e/datasets/*/tables/*` | `gs://dlap-gcp-e2e-*`, `GCP`, `GCS_BUCKET`; `bq://dlap-gcp-e2e/*/*`, `GCP`, `BIGQUERY_TABLE` |
| `azure-events.json` | AZURE | 3 × `https://dlapazuree2e.blob.core.windows.net/<container>` | `az://dlapazuree2e/<container>`, `AZURE`, `BLOB_CONTAINER` |

The filenames are **provider-prefixed** so the seeder's
`dlap.logingest.replay.provider` filter can select a single cloud (e.g. `aws`)
or, left blank, replay all three. Since CAT-02a (ADR-0055) `da-catalog` stores the
canonical URI from `AssetIdNormalizer` (ADR-0047) and takes `cloud_provider` and
`service_type` from it, so the fixtures only need documentation-shaped URIs; no
per-cloud parser is involved. The raw form of each `targetResourceId` is kept in
`native_asset_id`.

Object-level events roll up to their container (ADR-0046): the Azure ACCESS,
UPDATE and PERMISSION events name blobs and move the container's
`last_accessed_at`, `last_modified_at` and `last_permission_change_at`; they create
no blob rows. The Azure CREATE and DELETE events name the containers that their
`containers/write` and `containers/delete` operations act on.

The provider-**native** audit-log fixtures these are modeled on (AWS CloudTrail,
GCP Cloud Audit Logs, Azure Activity Log) live alongside each cloud's directory
under `../aws`, `../gcp`, `../azure`.

Driven by [`../../cloud-pipeline-e2e.sh`](../../cloud-pipeline-e2e.sh).
