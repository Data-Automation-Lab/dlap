# Asset ID: the canonical asset identifier

Spec version: 1.0 | Status: normative | Decision: ADR-0047 | Implementation: `da-protos/src/main/java/com/dlap/common/assetid/`

## 1. Purpose

Every DLAP component that names a data asset (crawlers, log parsers, lineage, the catalog,
archive, delete, the policy engine) must produce the same identifier for the same resource. Today
they do not. The S3 crawler writes `s3://b` while the CloudTrail source writes `b`
(`S3ResourceCrawler.java:145`, `CloudTrailLogSource.java:249`). The BigQuery crawler writes
`p:d.t` while audit logs carry `projects/p/datasets/d/tables/t` and the delete path accepts only
`bq://p/d/t` or `p.d.t` (`BigQueryCrawler.java:295`, `GcpCloudDeletionService.java:413-441`). So
log events rarely join to catalog assets.

This spec defines one canonical form per asset kind and the full set of aliases that normalize to
it. `AssetIdNormalizer` is the only code that builds or parses asset identifiers (plan rule R8).

## 2. Rules

1. **One rule for the form.** Short-scheme URIs where the ecosystem has one (`s3://`, `gs://`,
   `az://`); provider-native IDs otherwise (the AWS ARN, the Azure ARM resource ID).
2. **Identity is the URI.** Two IDs are the same asset exactly when their canonical URIs are equal.
   The URI is what `dlap_assets.asset_uri`, Kafka keys and lineage endpoints carry.
3. **Scope is identity only when the native ID contains it.** Account, project, region and
   location are attributes, not identity, unless the native identifier includes them (an ARN
   includes region and account; an S3 URI does not).
4. **Raw keys.** Object keys and blob paths are stored as raw UTF-8. Only `http(s)` URLs are
   percent-decoded on input; `s3://`, `gs://`, `az://`, `abfss://` and ARNs carry raw keys, so a
   `%` there is part of the key. Encoding applies only when rendering a URL.
5. **Never guess.** A form that needs context it does not have is rejected (section 8). Destructive
   callers (archive, delete) fail closed on any rejection.
6. **Idempotent.** Normalizing a canonical URI returns the same URI, and the same kind with one
   exception: a Glue Python shell job ARN reads as `SPARK_JOB` unless the `PYTHON_JOB` kind hint is
   passed again, because the ARN does not say which it is. Identity is the URI, so equality holds.

## 3. Canonical forms

| Kind | Provider | Canonical URI | Parts | Parent |
|---|---|---|---|---|
| `S3_BUCKET` | `AWS` | `s3://{bucket}` | bucket | none |
| `S3_OBJECT` | `AWS` | `s3://{bucket}/{key}` | bucket, key | `S3_BUCKET` |
| `RDS_INSTANCE` | `AWS` | `arn:{partition}:rds:{region}:{account}:db:{id}` | the five ARN fields | none |
| `REDSHIFT_CLUSTER` | `AWS` | `arn:{partition}:redshift:{region}:{account}:cluster:{name}` | the five ARN fields | none |
| `DYNAMODB_TABLE` | `AWS` | `arn:{partition}:dynamodb:{region}:{account}:table/{name}` | the five ARN fields | none |
| `GLUE_DATABASE` | `AWS` | `arn:{partition}:glue:{region}:{account}:database/{db}` | the five ARN fields | none |
| `GLUE_TABLE` | `AWS` | `arn:{partition}:glue:{region}:{account}:table/{db}/{table}` | the five ARN fields | `GLUE_DATABASE` |
| `SPARK_JOB` (Glue job) | `AWS` | `arn:{partition}:glue:{region}:{account}:job/{name}` | the five ARN fields | none |
| `PYTHON_JOB` (Glue Python shell job) | `AWS` | same ARN; the kind comes from the kind hint | the five ARN fields | none |
| `SPARK_JOB` (EMR step) | `AWS` | `arn:{partition}:elasticmapreduce:{region}:{account}:cluster/{id}/step/{id}` | the five ARN fields | none |
| `REDSHIFT_QUERY` (Athena query) | `AWS` | `athena://{region}/{account}/{queryExecutionId}` | region, account, queryExecutionId | none |
| `GCS_BUCKET` | `GCP` | `gs://{bucket}` | bucket | none |
| `GCS_OBJECT` | `GCP` | `gs://{bucket}/{object}` | bucket, object | `GCS_BUCKET` |
| `BIGQUERY_DATASET` | `GCP` | `bq://{project}/{dataset}` | project, dataset | none |
| `BIGQUERY_TABLE` | `GCP` | `bq://{project}/{dataset}/{table}` | project, dataset, table | `BIGQUERY_DATASET` |
| `BIGQUERY_JOB` | `GCP` | `bqjob://{project}/{jobId}` | project, jobId | none |
| `CLOUD_SQL_INSTANCE` | `GCP` | `//cloudsql.googleapis.com/projects/{project}/instances/{instance}` | project, instance | none |
| `BLOB_STORAGE` | `AZURE` | `az://{account}` | account | none |
| `BLOB_CONTAINER` | `AZURE` | `az://{account}/{container}` | account, container | `BLOB_STORAGE` |
| `BLOB_OBJECT` | `AZURE` | `az://{account}/{container}/{path}` | account, container, path | `BLOB_CONTAINER` |
| `SQL_DATABASE` | `AZURE` | ARM ID, lower-cased: `/subscriptions/{s}/resourcegroups/{rg}/providers/microsoft.sql/servers/{server}/databases/{db}` | subscription, resourceGroup, server, database | none |
| `COSMOS_DB` | `AZURE` | ARM ID, lower-cased: `.../microsoft.documentdb/databaseaccounts/{a}[/sqldatabases/{d}[/containers/{c}]]` | subscription, resourceGroup, account(, database(, container)) | none |
| `DATAFLOW_JOB` (Data Factory pipeline) | `AZURE` | ARM ID, lower-cased: `.../microsoft.datafactory/factories/{f}/pipelines/{p}` | subscription, resourceGroup, factory, pipeline | none |
| `UNITY_CATALOG` | `UNITY` | `uc://{metastore}/{catalog}` | metastore, catalog | none |
| `UNITY_SCHEMA` | `UNITY` | `uc://{metastore}/{catalog}/{schema}` | metastore, catalog, schema | `UNITY_CATALOG` |
| `UNITY_TABLE` | `UNITY` | `uc://{metastore}/{catalog}/{schema}/{table}` | metastore, catalog, schema, table | `UNITY_SCHEMA` |
| `UNITY_VOLUME` | `UNITY` | `uc://{metastore}/{catalog}/{schema}/volumes/{volume}` | metastore, catalog, schema, volume | `UNITY_SCHEMA` |
| `DATABRICKS_JOB` (job) | `DATABRICKS` | `dbx://{workspaceId}/jobs/{jobId}` | workspaceId, `jobs`, jobId | none |
| `DATABRICKS_JOB` (run) | `DATABRICKS` | `dbx://{workspaceId}/runs/{runId}` | workspaceId, `runs`, runId | the job, when the job ID is known |
| `DATABRICKS_JOB` (SQL query) | `DATABRICKS` | `dbx://{workspaceId}/queries/{queryId}` | workspaceId, `queries`, queryId | none |
| `SNOWFLAKE_TABLE` | `SNOWFLAKE` | `snowflake://{account}/{database}/{schema}/{table}` | account, database, schema, table | none |

The ARN parts are `[partition, service, region, account, resource]`, for example
`[aws, rds, eu-west-1, 123456789012, db:prod-postgres]`.

Kinds with no canonical scheme yet: `DATA_LAKE` (a catalog fallback type that no source emits)
and `DBT_MODEL` (no producer). `OCI` has no scheme.

## 4. Case rules

| Component | Rule | Why |
|---|---|---|
| URI schemes, ARN partition and service | lower-cased | case-insensitive by definition |
| S3 bucket names | **kept as given** | buckets created in us-east-1 before 2018-03-01 may contain upper case; every AWS-emitted form carries the exact name |
| RDS DB instance and Redshift cluster identifiers | lower-cased | AWS stores RDS identifiers in lower case; Redshift requires lower-case cluster identifiers (CreateCluster API reference) |
| Glue database and table names | lower-cased | Glue folds them to lower case when it stores them, for Hive compatibility (Glue Database and Table API reference, read 2026-10-18); Athena SQL may spell them in any case |
| Other ARN resource names (DynamoDB tables, Glue jobs, EMR IDs) | kept as emitted | "the ARN as emitted by AWS" |
| GCS bucket names, GCP project IDs | lower-cased | GCP allows only lower case |
| BigQuery dataset and table names | kept | case-sensitive by default |
| Azure storage account and container names | lower-cased | Azure allows only lower case |
| Azure ARM IDs (non-storage kinds) | lower-cased | ARM compares IDs case-insensitively and exports them in mixed case; Data Factory names are case-insensitive (ADF naming-rules page, ms.date 2025-03-31) |
| Object keys, GCS object names, blob paths | kept | case-sensitive |
| Unity Catalog metastore, catalog, schema, table, volume | lower-cased | Unity Catalog stores object names in lower case |
| Snowflake account | lower-cased | |
| Snowflake database, schema, table in a bare `db.schema.table` | unquoted: upper-cased; double-quoted: kept exactly | Snowflake stores and resolves unquoted identifiers in upper case and quoted ones as written |
| Snowflake names inside `snowflake://` | kept exactly | the canonical form holds stored names |

## 5. Input handling

- Leading whitespace and trailing CR or LF are removed. Trailing spaces are kept, because they can
  be part of an object key.
- One pair of wrapping double quotes is removed (StorageBlobLogs quotes `ObjectKey`), except for
  Snowflake input.
- Query strings and fragments are dropped from `http(s)` URLs before parsing, so SAS tokens and
  `versionId` never reach an ID or a log line.
- BigQuery table decorators (`$partition`, `@snapshot`) are stripped; the decorator is kept as the
  `decorator` attribute. A wildcard table (`events_*`) is rejected.
- Backticks are removed from BigQuery and Databricks names; double quotes from Glue (Athena) names.
- An ARM name segment may contain inner spaces (Data Factory pipeline names allow them). A segment
  with a slash, a control character, or leading or trailing whitespace is rejected.
- Inputs over 8192 characters, inputs with a NUL, and canonical URIs over 2048 code points
  (the width of `dlap_assets.asset_uri`) are rejected.
- Rejection details never echo the input.

## 6. Accepted aliases

Every alias in the table normalizes to the canonical form of its row. The tests
`AssetIdAliasConvergenceTest` (fixed cases) and `AssetIdSeededAliasTest` (seeded generator)
enforce it.

| Resource | Aliases (source) | Hints needed |
|---|---|---|
| S3 bucket | `s3://b` (`S3ResourceCrawler.java:145`); `arn:aws:s3:::b` (`e2e-tests/fixtures/aws/cloudtrail-sample.json`); bare `b` (`CloudTrailLogSource.java:249`); `s3a://b`, `s3n://b`; `https://b.s3[.region].amazonaws.com`, `https://s3[.region].amazonaws.com/b` | bare `b`: kind `S3_BUCKET` |
| S3 object | `s3://b/k`; `arn:aws:s3:::b/k`; `s3a://`, `s3n://`; virtual-hosted and path-style https URLs; bare `b/k` | bare: kind `S3_OBJECT` |
| RDS instance | ARN (`RdsResourceCrawler.java:148`); `rds://region/id` (`AwsCloudDeletionService.java:574-587`); bare id (CloudTrail `resourceName`) | `rds://`: account; bare: kind, region, account |
| DynamoDB table | ARN; `dynamodb://region/table` (`AwsCloudDeletionService.java:589-602`); bare name | `dynamodb://`: account; bare: kind, region, account |
| Redshift cluster | ARN; bare name | bare: kind, region, account |
| Glue database, table | ARN (`GlueResourceCrawler.java:183`); bare `db`, `db.table`, `AwsDataCatalog.db.table`, `"db"."table"`, in any case | bare: kind, region, account |
| Glue job, EMR step | ARN (`AwsProcessingJobResourceCrawler.java:202,266`) | `PYTHON_JOB` kind hint for a Python shell job |
| Athena query | `athena://region/query/id` (`AwsProcessingJobResourceCrawler.java:237`); `athena://region/account/id`; bare id | `query` form: account; bare: kind, region, account |
| GCS bucket | `gs://b`; `projects/_/buckets/b` (GCS audit `resourceName`); `//storage.googleapis.com/projects/_/buckets/b`; `//storage.googleapis.com/b`; bare `b` (`GCSResourceCrawler.java:255`); `https://storage.googleapis.com/b`; `https://b.storage.googleapis.com/`; `https://www.googleapis.com/storage/v1/b/b` | bare: kind `GCS_BUCKET` |
| GCS object | `gs://b/o` (`GCSResourceCrawler.java:303` name field); `projects/_/buckets/b/objects/o`; the `//storage.googleapis.com/` form; XML-API https URLs; JSON-API `.../storage/v1/b/b/o/{encoded}`; bare `b/o` | bare: kind `GCS_OBJECT` |
| BigQuery dataset | `GenericData{classInfo=[datasetId, projectId], {datasetId=d, projectId=p}}` (`BigQueryCrawler.java:268` stores `DatasetId.toString()`; this is its output with google-cloud-bigquery 2.50.0); `projects/p/datasets/d`; `//bigquery.googleapis.com/projects/p/datasets/d`; `bq://p/d`; `bq://p.d`; `p:d`; bare `d`; `https://bigquery.googleapis.com/bigquery/v2/projects/p/datasets/d` | `p:d`: provider `GCP` or kind; bare `d`: kind and project |
| BigQuery table | `p:d.t` (`BigQueryCrawler.java:295`); `projects/p/datasets/d/tables/t` (audit logs, `DefaultLineageExtractor.java:487-504`, `GcpBigQueryJobResourceCrawler.java` table URIs); `p.d.t` and backticked forms (SQL lineage, `DefaultLineageExtractor.java:404-421`); `bq://p/d/t`, `bq://p.d.t`, `bq://p/d.t` (`GcpCloudDeletionService.java:413-441`); the `//bigquery.googleapis.com/` form; GenericData with `tableId`; bare `d.t`; domain-scoped projects (`example.com:p`) in every form | `p.d.t`: provider `GCP` or kind; `d.t`: also the project |
| BigQuery job | `bigquery://projects/p/jobs/j` (`GcpBigQueryJobResourceCrawler.java:155`); `projects/p/jobs/j`; `//bigquery.googleapis.com/projects/p/jobs/j`; `bqjob://p/j`, `bqjob://p/{location or _}/j`; `p:location.j`; `projects/p/locations/l/jobs/j`; GenericData with `jobId`; bare `j` | `p:location.j` and `projects/p/locations/l/jobs/j`: kind `BIGQUERY_JOB`; bare: kind and project |
| Cloud SQL instance | `//cloudsql.googleapis.com/projects/p/instances/i`; `//sqladmin.googleapis.com/...`; `projects/p/instances/i`; connection name `p:region:i`; bare `i` (`CloudSQLInstanceCrawler.java:204`) | all but the `//` forms: kind `CLOUD_SQL_INSTANCE`; bare: also the project |
| Azure account, container, blob | `az://a/c/p` (`AssetLocationResolver.java:184-198`); `azure://a/c/p` (`AzureCloudDeletionService.java:591-648`); `abfss://c@a.dfs.core.windows.net/p`, `abfs://` (`AzureCloudStorageService.java:352-402`); `wasbs://c@a.blob.core.windows.net/p`, `wasb://`; `https://a.blob.core.windows.net/c/p` and `.dfs.` (`e2e-tests/fixtures/replay/azure-events.json`); ARM storage account, `blobServices/default` and container IDs (`e2e-tests/fixtures/azure/activity-log-sample.json`); StorageBlobLogs `ObjectKey` `/a/c/p`, quoted or not; bare names | `ObjectKey`: provider `AZURE` or a blob kind; bare container: kind and account |
| Azure SQL database | ARM ID in any case; `azuresql://s/rg/server/db` (`AzureCloudDeletionService.java:564-581`) | none |
| Cosmos DB | ARM ID; `azurecosmos://a/db/container` (`AzureCloudDeletionService.java:545-562`) | `azurecosmos://`: subscription and resource group |
| Data Factory pipeline | ARM ID | none |
| Unity Catalog table, schema, catalog | `c.s.t` (`UnityCatalogResource.java:78`; audit `full_name_arg`), backticked, any case; `uc://` | metastore, and provider `UNITY` or `DATABRICKS` or a Unity kind |
| Unity Catalog volume | `/Volumes/c/s/v`, `dbfs:/Volumes/c/s/v`; `c.s.v` | metastore; `c.s.v` needs kind `UNITY_VOLUME` |
| Databricks run, job, query | `databricks://ws/jobs/runs/id`, `databricks://ws/sql/queries/id` (`DatabricksProcessingJobResourceCrawler.java:161,179`); `databricks://ws/jobs/id`; `dbx://ws/jobs/j/runs/r` (design B4 form); `dbx://` canonical forms | the `workspaceId` hint when the workspace segment is not numeric and not an `adb-{id}.{n}.azuredatabricks.net` host |
| Snowflake table | `db.schema.table` with Snowflake quoting; `snowflake://` | account, and provider `SNOWFLAKE` or kind `SNOWFLAKE_TABLE` |

Forms that are recognised and deliberately rejected:

- `azure://data-factory/{sub}/pipelines/{id}` and `azure://synapse/{sub}/sparkjobs/{id}`
  (`AzureProcessingJobResourceCrawler.java:164,183`). They come from a stub client, carry no
  resource group, and the synapse form collides with a blob path. DISC-02 must emit the ARM ID.
  The synapse form (second segment a subscription GUID, third `sparkjobs`) is rejected even
  without a hint; only a blob kind hint reads it as a blob in a storage account named `synapse`.
- The bare GCS object key (`GCSResourceCrawler.java:303` resource ID): keys collide across buckets.
  Use the `gs://b/o` name or `build`.
- An ARN whose account is not 12 digits. `RdsResourceCrawler.java:148` and
  `GlueResourceCrawler.java:183` concatenate `getAccountId()`, which can be null and then yields
  `...:null:...`.
- A file inside a Unity Catalog volume (`/Volumes/c/s/v/path`): the volume is the asset.
- ARM extension resources (role assignments, locks under a scope): normalize the scope instead.

## 7. Hints

`AssetIdHints` carries context from the source binding or the event. Hints never override a
self-describing form: `gs://b` stays a GCS bucket whatever the provider hint says. The kind hint
picks between readings of an ambiguous form and refines a Glue job to `PYTHON_JOB`.

| Hint | Meaning |
|---|---|
| `provider` | the provider of a bare form |
| `kind` | the expected kind of a bare or ambiguous form; its provider is used when `provider` is absent |
| `account` | AWS account ID (12 digits), Azure storage account, or Snowflake account, by provider |
| `project` | the default GCP project for `dataset`, `dataset.table`, job and instance names |
| `region` | the AWS region for bare AWS names |
| `subscription`, `resourceGroup` | Azure scope for `azurecosmos://` |
| `metastoreId` | the Unity Catalog metastore; three-part names are unique only inside a metastore |
| `workspaceId` | the numeric Databricks workspace ID |

## 8. Rejections

`AssetIdNormalizer.parse` returns `Normalized` or `Rejected(reason, detail, raw)`.

| Reason | Meaning | Typical caller action |
|---|---|---|
| `BLANK` | null or blank input | skip |
| `UNRECOGNIZED` | no scheme recognises the form | count; quarantine a log record |
| `AMBIGUOUS` | more than one reading (`p.d.t` without a provider; `projects/p/instances/i` without a kind) | pass the hint |
| `MISSING_HINT` | recognised, but a needed hint is absent (account, project, metastore...) | fix the binding |
| `MALFORMED` | recognised, but a component is invalid | quarantine |
| `UNSUPPORTED_KIND` | names something that is not a DLAP asset kind (IAM user, KMS key, index) | ignore for asset state |
| `TOO_LONG` | input or canonical URI too long | quarantine |

Archive and delete must treat every rejection as "do not act" (fail closed).

## 9. Kinds, service types and providers

Each `AssetKind` maps to exactly one `dlap_assets.service_type`, equal to its name. The list is
frozen for wave 1: the 22 service types in `DlapConstants.ServiceType` plus `S3_OBJECT`,
`GCS_OBJECT`, `BLOB_CONTAINER`, `BLOB_OBJECT`, `GLUE_DATABASE`, `GLUE_TABLE`, `UNITY_VOLUME` and
`SNOWFLAKE_TABLE`. CAT-01 adds the eight to `ck_asset_service_type_valid`.

| Service type | Default provider |
|---|---|
| `S3_BUCKET`, `S3_OBJECT`, `RDS_INSTANCE`, `REDSHIFT_CLUSTER`, `DYNAMODB_TABLE`, `GLUE_DATABASE`, `GLUE_TABLE`, `REDSHIFT_QUERY` | `AWS` |
| `GCS_BUCKET`, `GCS_OBJECT`, `BIGQUERY_DATASET`, `BIGQUERY_TABLE`, `BIGQUERY_JOB`, `CLOUD_SQL_INSTANCE` | `GCP` |
| `BLOB_STORAGE`, `BLOB_CONTAINER`, `BLOB_OBJECT`, `SQL_DATABASE`, `COSMOS_DB`, `DATA_LAKE` | `AZURE` |
| `UNITY_CATALOG`, `UNITY_SCHEMA`, `UNITY_TABLE`, `UNITY_VOLUME` | `UNITY` |
| `DATABRICKS_JOB` | `DATABRICKS` |
| `SNOWFLAKE_TABLE` | `SNOWFLAKE` |
| `SPARK_JOB`, `PYTHON_JOB`, `DATAFLOW_JOB`, `DBT_MODEL` | shared; the ID's provider decides |

`AssetProvider` values are the `dlap_assets.cloud_provider` values: `AWS`, `GCP`, `AZURE`, `OCI`,
`UNITY` (as in `DlapConstants.CloudProvider`) plus `DATABRICKS` and `SNOWFLAKE`, which CAT-01 adds
to `ck_asset_cloud_provider_valid`. Unity Catalog securables are `UNITY`; Databricks workspace
objects (jobs, runs, queries) are `DATABRICKS`.

The provider and kind come from the canonical ID. The catalog's keyword heuristics, including the
GCP default for anything unrecognised (`DiscoveryAssetDiscoveredListener.java:133-211`), are
replaced by `CanonicalAssetId.provider()` and `kind()` in CAT-02.

## 10. Parent and roll-up

`parent()` returns the container one level up (table of the parent column in section 3).
`rollUp()` returns the parent for the object-level kinds (`S3_OBJECT`, `GCS_OBJECT`,
`BLOB_OBJECT`) and the asset itself otherwise. Under ADR-0046 object events roll up to the parent
container; object rows exist only for governed prefixes.

## 11. OpenLineage mapping

From the OpenLineage naming conventions (https://openlineage.io/docs/spec/naming, page version
1.54.0, read 2026-10-18). `OpenLineageNaming.toDataset` and `fromDataset` implement it and
round-trip.

| Kind | Namespace | Name |
|---|---|---|
| `S3_BUCKET`, `S3_OBJECT` | `s3://{bucket}` | `/` for the bucket; `{key}` |
| `GCS_BUCKET`, `GCS_OBJECT` | `gs://{bucket}` | `/`; `{object}` |
| `BLOB_CONTAINER`, `BLOB_OBJECT` | `abfss://{container}@{account}.dfs.core.windows.net` | `/`; `{path}` |
| `BIGQUERY_TABLE` | `bigquery` | `{project}.{dataset}.{table}` |
| `GLUE_TABLE` | `arn:{partition}:glue:{region}:{account}` | `table/{database}/{table}` |
| `SNOWFLAKE_TABLE` | `snowflake://{account}` | `{database}.{schema}.{table}` |
| `UNITY_TABLE` | `unitycatalog://{workspace host}` | `{catalog}.{schema}.{table}` |

Notes:

- The naming page does not define a bucket-level name; DLAP uses `/`.
- OpenLineage names Unity Catalog tables by workspace host, DLAP by metastore. Mapping out needs
  the host; mapping in needs the `metastoreId` hint.
- Inbound `wasbs://` namespaces are accepted as well as `abfss://`.
- Kinds without an OpenLineage dataset form (jobs, accounts, datasets, instances) map to empty.

## 12. Java API

```java
AssetIdParseResult r = AssetIdNormalizer.parse(raw, AssetIdHints.builder()
        .provider(AssetProvider.GCP).project("my-project").build());
Optional<CanonicalAssetId> id = AssetIdNormalizer.normalize("projects/_/buckets/b/objects/o");
AssetIdParseResult built = AssetIdNormalizer.build(AssetKind.BIGQUERY_TABLE, List.of(p, d, t));
boolean canonical = AssetIdNormalizer.isCanonical(assetUri);
```

`CanonicalAssetId` exposes `provider()`, `kind()`, `parts()`, `uri()`, `attributes()`,
`cloudProvider()`, `serviceType()`, `parent()` and `rollUp()`. `AssetIdParseResult.raw()` keeps the
input for `dlap_assets.native_asset_id` ("the first native form seen").

## 13. Adoption

| Call sites | Package |
|---|---|
| Crawlers (`S3ResourceCrawler`, `GCSResourceCrawler`, `BigQueryCrawler`, `RdsResourceCrawler`, `GlueResourceCrawler`, `UnityCatalogResource`, job crawlers), `CrawlerAssetSpineBridge`, `LogAnalyzerSpinePublisher` | DISC-02 |
| Log parsers in da-logingest | LOG-02 and the wave-3 parser packages |
| `DiscoveryAssetDiscoveredListener` provider and type derivation, catalog listener lookups, `/assets/resolve` | CAT-02 |
| `AssetLocationResolver`, `AssetLocatorResolver`, `GcpResourceRef.parse`, `AwsResourceRef.parse`, `GcsArchiveStorageProvider`, `AwsS3GlacierArchivalProvider` | ID-02 |
| `DefaultLineageExtractor.tableUri` and `normalizeTable` | LIN-01 |
| Existing `asset_uri` rows | ID-03 (dry-run report, reversible map, merge) |

Kafka keys become the canonical URI after every writer emits canonical IDs (ID-03 runbook).

## 14. Differences from the design (research/hardening/06 B4)

| Design B4 | This spec | Reason |
|---|---|---|
| bucket names lower-cased | S3 bucket names kept as given | legacy us-east-1 buckets may contain upper case; lower-casing could point a delete at a different bucket |
| `bqjob://{project}/{location or _}/{jobId}` | `bqjob://{project}/{jobId}`; location is an attribute; both B4 forms are accepted aliases | the crawler and audit job names often lack the location, so a location in identity splits one job into two IDs |
| `dbx://{ws}/jobs/{jobId}[/runs/{runId}]` | runs are `dbx://{ws}/runs/{runId}`; the job ID is an attribute and the parent; the B4 form is an accepted alias | the Jobs API documents `run_id` as unique across all runs of all jobs, and the crawler form has no job ID |
| no Snowflake, volume or Cloud SQL rows | added | the frozen kind list includes `SNOWFLAKE_TABLE` and `UNITY_VOLUME`; `CLOUD_SQL_INSTANCE` is an existing type |
| Athena `athena://{region}/{account}/{id}` | same; the crawler's `athena://{region}/query/{id}` is an alias that needs the account hint | |

## 15. Known limits

- BigQuery job IDs are assumed unique per project across locations. The JobReference reference
  page (last updated 2025-07-02) does not state the scope [unverified].
- Snowflake: whether `ACCESS_HISTORY` reports case-sensitive (quoted) names with quotes is
  [unverified]. A source that reports stored names must quote names that are not all upper case,
  or call `build` with the stored names (SNOW-01).
- Cloud SQL full resource name: `//cloudsql.googleapis.com/` is assumed to be the Cloud Asset
  Inventory form [unverified]; `//sqladmin.googleapis.com/` is accepted as an alias.
- Sovereign clouds: `az://` drops the endpoint suffix, so an account in Azure China and one with
  the same name in Azure public cloud share an ID.
- OCI has no scheme.

## 16. Versioning

The spec version is `AssetIdNormalizer.SPEC_VERSION`. A change to a canonical form is a breaking
change: it needs an ADR, a version bump, a re-key plan (ID-03 runner) and re-run consumer
contract tests. Adding an alias or a kind is additive.
