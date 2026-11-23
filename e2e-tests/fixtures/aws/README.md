# AWS synthetic E2E fixtures

> **Location note:** the normalized replay array (called `normalized-events.json`
> below) now lives at [`../replay/aws-events.json`](../replay/aws-events.json) so
> `FixtureReplaySeeder` can select it by provider filename prefix. The native
> CloudTrail fixtures stay in this directory.

Provider-shaped inputs and the normalized events that drive the offline
E2E harness for the AWS discovery / catalog spine. All identifiers,
account IDs, and IP addresses are synthetic (RFC 5737 documentation
ranges, `123456789012` example account).

## Files

| File | Shape | Feeds |
|---|---|---|
| `cloudtrail-sample.json` | Native **AWS CloudTrail record** batch (`{ "Records": [...] }`) | The AWS log adapter under test (`da-discovery`), which parses CloudTrail JSON. Covers S3 `PutObject` + RDS `CreateDBInstance`. |
| `cloudtrail-extended.json` | Native **AWS CloudTrail record** batch | Extends the sample with S3 `DeleteObject` and `PutBucketAcl` (a permission change). Same record schema as the sample. |
| `lifecycle-log-sources.yml` | da-logingest log-source configuration (YAML) | The retire phase of `cloud-lifecycle-e2e.sh aws` (RET-08, Q-714): one CloudTrail binding on the `S3_TRAIL_FILES` transport reading the LocalStack bucket `dlap-e2e-trail`. The harness writes one trail file there at run time (an `s3lifecycle.amazonaws.com` `DeleteObject`, invented ids) to prove the applied rule. Mounted by `../../docker-compose.lifecycle-aws.yml`. |
| `normalized-events.json` | JSON array of `IngestRequestDTO` (flat camelCase) | The offline harness posts this array to `ILogIngestService.ingest()` / `POST /api/v1/logingest/events`. This is the **normalized** tier - already past provider parsing. |

### Native vs. normalized

The two `cloudtrail-*.json` files mirror what AWS actually emits: a
**CloudTrail record** as documented in the AWS CloudTrail *record
contents* reference (`eventVersion`, `eventSource`, `eventName`,
`awsRegion`, `userIdentity`, `requestParameters`, `resources`, ...).
They exercise the provider-parsing layer.

`normalized-events.json` is the post-normalization tier: each element is
an `IngestRequestDTO` (`da-logingest`, `com.dlap.logingest.dto`) - the
JSON-friendly mirror of `dlap.log.v1.DlapLogEvent`. The offline harness
feeds this array straight to `ILogIngestService.ingest()`, bypassing
provider parsing so catalog behavior can be asserted deterministically.

For reference, the sibling clouds normalize from:

- **GCP**: Cloud Audit Logs `AuditLog` `protoPayload`
  (`serviceName`, `methodName`, `authenticationInfo`, `resourceName`).
- **Azure**: Activity Log `eventProperties`
  (`operationName`, `resourceId`, `caller`, `status`).

## Asset URI derivation (catalog)

`da-catalog` derives `cloud_provider` and `service_type` **from the
asset URI** (`targetResourceId`), not from `sourceProvider`. Rules live
in `DiscoveryAssetDiscoveredListener.deriveCloudProvider` /
`deriveServiceType` (`da-catalog`). For these fixtures:

- `s3://` prefix → `cloud_provider = AWS`, `service_type = S3_BUCKET`.

### URIs used in `normalized-events.json`

| Asset URI (`targetResourceId`) | cloud_provider | service_type |
|---|---|---|
| `s3://dlap-aws-e2e-logs` | AWS | S3_BUCKET |
| `s3://dlap-aws-e2e-finance` | AWS | S3_BUCKET |
| `s3://dlap-aws-e2e-analytics` | AWS | S3_BUCKET |

Only `changeType = CREATE` upserts a new catalog row, so the three
distinct URIs above yield **three distinct catalog rows**. The
non-CREATE events (`ACCESS`, `UPDATE`, `PERMISSION`, `DELETE`) target
those same three URIs and update/annotate the existing rows rather than
creating new ones.

### Event breakdown (`normalized-events.json`, 8 events)

| requestId | operation | changeType | targetResourceId |
|---|---|---|---|
| `aws-evt-0001-create-logs` | PutBucket | CREATE | `s3://dlap-aws-e2e-logs` |
| `aws-evt-0002-create-finance` | PutBucket | CREATE | `s3://dlap-aws-e2e-finance` |
| `aws-evt-0003-create-analytics` | PutBucket | CREATE | `s3://dlap-aws-e2e-analytics` |
| `aws-evt-0004-access-finance` | GetObject | ACCESS | `s3://dlap-aws-e2e-finance` |
| `aws-evt-0005-update-analytics` | PutObject | UPDATE | `s3://dlap-aws-e2e-analytics` |
| `aws-evt-0006-permission-finance` | PutBucketAcl | PERMISSION | `s3://dlap-aws-e2e-finance` |
| `aws-evt-0007-access-analytics` | GetBucketTagging | ACCESS | `s3://dlap-aws-e2e-analytics` |
| `aws-evt-0008-delete-logs` | DeleteBucket | DELETE | `s3://dlap-aws-e2e-logs` |
