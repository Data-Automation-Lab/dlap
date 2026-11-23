<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Guide: cloud permissions for a read-only pilot

DLAP reads a customer's cloud with one identity per cloud, the **Reader**. The Reader discovers
assets, ingests audit logs and checks read coverage. It holds no write permission, and it reads
object or table content nowhere except the audit-log export the customer points it at.

Archive and delete use separate **Actor** roles, one per retire executor (da-autodelete and
da-autoarchival), granted only on the buckets, datasets, storage accounts and containers the
operator names. They are not part of a read-only pilot; see
[Actor roles](#actor-roles-archive-and-delete) (IAM-04). Label write-back and grant projection have
no Actor role yet.

da-admin-service checks the Actor roles against the committed traces from the running system: see
[The permission self-check](#the-permission-self-check). How DLAP's own runtime identity reaches
the customer's roles is in
[DLAP's runtime identity and cross-account trust](#dlaps-runtime-identity-and-cross-account-trust).

## How the roles are built

Each cloud has one trace file, `deployment/iam/<cloud>/reader/permissions.yaml`. Every entry
names a permission, the scope it is granted on, why DLAP needs it, and every call site that needs
it, as `file:line` plus the call text on that line. Nothing else lists permissions:

| Cloud | Deploy with | Reads the trace |
|---|---|---|
| GCP | `deployment/iam/gcp/reader/` (Terraform) | `yamldecode` |
| AWS | `deployment/iam/aws/reader/reader-role.cfn.yaml` (CloudFormation) or `deployment/iam/aws/reader/` (Terraform) | Terraform: `yamldecode`. CloudFormation repeats the list; a test fails when they differ. |
| Azure | `deployment/iam/azure/reader/` (Terraform) | `yamldecode` |
| Databricks | `deployment/iam/databricks/reader/` (Terraform) | `yamldecode` |

`ReaderRoleTraceabilityTest` (da-discovery, runs with `:da-discovery:test`) keeps the traces
honest. It fails when:

- a call site no longer exists, or its line no longer holds the quoted call;
- a GCP permission has a verb other than `list`, `listAll`, `get`, `getIamPolicy` or `getData`
  without a written exception (only `bigquery.jobs.create` has one, see below);
- `storage.objects.get` is granted anywhere but the log sink bucket, or `bigquery.tables.getData`
  anywhere but the log sink dataset;
- a log sink role holds a permission of another resource type (the bucket role holds `storage.*`
  only, the dataset role `bigquery.*` only);
- an AWS action is not a List, Get, Describe or LookupEvents action, or reads object or query
  content (`s3:GetObject`, `athena:GetQueryResults` and the like), except `s3:GetObject` on the
  `log_bucket_objects` scope only;
- an AWS log bucket statement (CloudFormation or Terraform) names a resource other than the log
  bucket parameters or variables, for example `*`, a literal bucket ARN or a wildcard bucket;
- an Azure action does not end in `/read`, or the role has a data action;
- a Databricks privilege is anything but `BROWSE`, `USE_CATALOG` or `USE_SCHEMA`;
- the CloudFormation template grants a different action set from the trace;
- a Terraform file hard-codes a permission instead of reading the trace;
- a file of da-discovery or da-logingest imports a cloud client but is neither cited nor listed
  under `no_permission_sources` with a reason;
- a COV-01 coverage client names a permission that no trace holds;
- this guide does not name a Reader permission.

So a change that adds a cloud call fails the build until its permission and call site are in the
trace. Add both in the same commit.

## GCP

### Apply

```bash
cd deployment/iam/gcp/reader
terraform init
terraform apply \
  -var project_id=<monitored-and-billing-project> \
  -var 'monitored_project_ids=["<another-project>"]' \
  -var log_sink_bucket=<audit-log-bucket> \
  -var log_sink_dataset=<audit-log-dataset> \
  -var organization_id=<org-number> -var 'folder_ids=["<folder-number>"]'
```

Only `project_id` is required. Leave out the sink variables when DLAP reads logs through the
Logging API only, and leave out `organization_id` when DLAP crawls single projects and the
project's own audit configs are enough.

Attach the Reader service account to the pilot VM. Application Default Credentials in the DLAP
containers then use the VM's metadata server: no key file exists. The module creates no key.

### Roles

| Role | Granted on | Permissions |
|---|---|---|
| `dlapReader` (project custom role, one per monitored project) | each monitored project | the `project` scope below |
| `dlapReaderOrg` (organization custom role, optional) | the organization or chosen folders | the `organization` scope below |
| `dlapLogSinkBucketReader` (project custom role in the sink project, optional) | the sink bucket only | the `log_sink_bucket` scope below |
| `dlapLogSinkDatasetReader` (project custom role in the sink project, optional) | the sink dataset only | the `log_sink_dataset` scope below |

The sink has two roles, one per resource type, so no role is granted on a resource that has none
of its permissions.

### Permissions

| Permission | Scope | Why | Call sites |
|---|---|---|---|
| `storage.buckets.list` | project | list buckets for discovery and coverage | `GCSResourceCrawler.java:167`, `GcpCoverageRestClient.java:100` |
| `storage.buckets.getIamPolicy` | project | flag public buckets, whose reads are never logged | `GcpCoverageRestClient.java:112` |
| `storage.objects.list` | project, log_sink_bucket | object names and metadata; log export shards | `GCSResourceCrawler.java:199`, `GcsLogFetcherStrategy.java:123`, `StorageShardStore.java:78` |
| `storage.objects.get` | log_sink_bucket | read log export shards | `GcsLogFetcherStrategy.java:139`, `StorageShardStore.java:89` |
| `bigquery.datasets.get` | project | list datasets (there is no `datasets.list` permission) | `BigQueryCrawler.java:143` |
| `bigquery.tables.list` | project, log_sink_dataset | list tables; list sharded sink tables | `BigQueryCrawler.java:178`, `BigQuerySinkTables.java:65` |
| `bigquery.tables.getData` | log_sink_dataset | query the exported audit-log tables | `BigQuerySinkTables.java:83`, `BigQueryLogFetcherStrategy.java:130` |
| `bigquery.jobs.create` | project | run the read-only queries (see the exception below) | `BigQueryJobsViewGateway.java:92,102,126`, `BigQuerySinkTables.java:83`, `BigQueryLogFetcherStrategy.java:130` |
| `bigquery.jobs.listAll` | project | job history of all users (jobs list, `INFORMATION_SCHEMA.JOBS_BY_PROJECT`) | `GcpBigQueryJobResourceCrawler.java:130`, `BqJobsQueries.java:85` |
| `cloudsql.instances.list` | project | list Cloud SQL instances | `CloudSQLInstanceCrawler.java:112` |
| `logging.logEntries.list` | project | Admin Activity audit logs | `GapicEntriesPager.java:67`, `GcpLogSource.java:580`, `GCPLoggingAdapter.java:77` |
| `logging.privateLogEntries.list` | project | Data Access audit logs, the read signal | same three calls |
| `resourcemanager.projects.get` | project | project parent, for inherited audit configs | `GcpCoverageRestClient.java:71` |
| `resourcemanager.projects.getIamPolicy` | project | project `auditConfigs` | `GcpCoverageRestClient.java:84` |
| `resourcemanager.folders.get` | organization | folder parent | `GcpCoverageRestClient.java:77` |
| `resourcemanager.folders.getIamPolicy` | organization | folder `auditConfigs` | `GcpCoverageRestClient.java:82` |
| `resourcemanager.organizations.getIamPolicy` | organization | organization `auditConfigs` | `GcpCoverageRestClient.java:83` |
| `resourcemanager.folders.list` | organization | folder-wide discovery: sub-folders | `GCPFolderManagementServiceImpl.java:125` |
| `resourcemanager.projects.list` | organization | folder-wide discovery: projects | `GCPFolderManagementServiceImpl.java:236` |

Full paths are in the trace file.

**The one exception: `bigquery.jobs.create`.** BigQuery runs every query, a dry run included, as
a job, so DLAP cannot read `INFORMATION_SCHEMA.JOBS_BY_PROJECT` or a sink table without it. A job
writes only its own temporary result. It cannot change customer data, because the Reader holds no
`bigquery.tables.create`, `updateData`, `update` or `delete` and no `bigquery.datasets.create`:
a DML, DDL or destination-table query is refused.

**Not granted, on purpose.**
- `storage.objects.get` and `bigquery.tables.getData` on data buckets and datasets. The Reader
  sees names, sizes and times, never content.
- `logging.views.access` and log bucket permissions. DLAP reads `entries.list` on the project,
  which the two `logEntries` permissions cover.
- `storage.buckets.get` with ACLs. The bucket list uses the API's default `noAcl` projection, so
  the crawler receives no ACL. Public access comes from `storage.buckets.getIamPolicy`.

### Live check

Emulators do not enforce IAM. After `terraform apply`, run:

```bash
deployment/iam/gcp/reader/live-check.sh <project-id> <reader-service-account-email>
```

It compares `dlapReader` with the trace, then calls `projects.testIamPermissions` as the Reader
and fails if any project-scope permission is missing or if the account holds any of 21 write or
content probes (for example `storage.objects.delete`, `bigquery.tables.updateData`,
`resourcemanager.projects.setIamPolicy`, or `storage.objects.get` project-wide). It reads only.
`live-check.sh --self-test` proves the script offline against stubbed tools.

## AWS

### Apply

CloudFormation:

```bash
aws cloudformation deploy --stack-name dlap-reader \
  --template-file deployment/iam/aws/reader/reader-role.cfn.yaml \
  --capabilities CAPABILITY_NAMED_IAM
```

With no parameters, the role trusts EC2 and the stack creates an instance profile: attach it to
the pilot VM. To let a principal in another account assume the role, pass
`TrustedPrincipalArn` and `ExternalId` (the template refuses a principal without an external id).
Terraform does the same with `trusted_principal_arn` and `external_id`.

When DLAP reads CloudTrail trail files or S3 server access logs from S3 (the da-logingest
`S3_TRAIL_FILES` and `S3_SERVER_ACCESS_LOGS` transports, ADR-0105), name the log buckets:
`--parameter-overrides CloudTrailLogBucket=<trail-bucket> S3AccessLogBucket=<access-log-bucket>`,
or Terraform `-var cloudtrail_log_bucket=<trail-bucket> -var s3_access_log_bucket=<access-log-bucket>`.
Each is a bucket name (an ARN or a wildcard is refused); leave out the one DLAP does not read. The
template then adds `ReaderLogBucketPolicy` (Terraform `<role_name>-log-buckets-policy`), which
grants the `log_bucket` scope on those bucket ARNs and the `log_bucket_objects` scope on their
objects. With neither set, the policy is not created and the Reader reads no object anywhere.

DLAP uses the AWS SDK default credentials chain, so the instance profile, `AWS_ROLE_ARN` with web
identity, or a profile with `role_arn` all work without an access key.

### Permissions (Resource `*`)

| Action | Why | Call sites |
|---|---|---|
| `s3:ListAllMyBuckets` | list buckets (S3 ListBuckets) | `S3ResourceCrawler.java:117`, `S3BucketInventory.java:74` |
| `s3:GetBucketLocation` | bucket region | `S3ResourceCrawler.java:160`, `S3BucketInventory.java:83` |
| `s3:GetBucketVersioning` | versioning state | `S3ResourceCrawler.java:172` |
| `s3:GetEncryptionConfiguration` | default encryption (S3 GetBucketEncryption) | `S3ResourceCrawler.java:183` |
| `glue:GetDatabases` | Glue databases | `GlueResourceCrawler.java:142` |
| `glue:GetTables` | Glue tables | `GlueResourceCrawler.java:160` |
| `glue:GetJobs` | Glue ETL jobs | `AwsProcessingJobResourceCrawler.java:190` |
| `athena:ListQueryExecutions` | Athena executions (primary workgroup) | `AwsProcessingJobResourceCrawler.java:229` |
| `athena:GetQueryExecution` | execution state and timing, not results | `AwsProcessingJobResourceCrawler.java:231` |
| `elasticmapreduce:ListClusters` | EMR clusters | `AwsProcessingJobResourceCrawler.java:264` |
| `elasticmapreduce:ListSteps` | EMR steps | `AwsProcessingJobResourceCrawler.java:266` |
| `rds:DescribeDBInstances` | RDS instances | `RdsResourceCrawler.java:120` |
| `ec2:DescribeInstances` | EC2 instances | `EC2InstanceCrawler.java:140` |
| `cloudtrail:DescribeTrails` | trails per region, shadow trails included | `AwsSignedJsonClient.java:134` |
| `cloudtrail:GetEventSelectors` | which S3 data events a trail records | `AwsSignedJsonClient.java:147` |
| `cloudtrail:GetTrailStatus` | whether a trail is logging | `AwsSignedJsonClient.java:159` |
| `cloudtrail:LookupEvents` | CloudTrail event history for ingest | `CloudTrailLogSource.java:390` |

STS GetCallerIdentity (`AwsSignedJsonClient.java:171`) needs no permission.

### Permissions on the named log buckets only

| Action | Scope | Resource | Why | Call sites |
|---|---|---|---|---|
| `s3:ListBucket` | log_bucket | `arn:<partition>:s3:::<log-bucket>` | list trail files and access log objects (ListObjectsV2), and the trail bucket's region folders | `SdkS3LogStore.java:137,153` |
| `s3:GetObject` | log_bucket_objects | `arn:<partition>:s3:::<log-bucket>/*` | read trail files and access log objects | `SdkS3LogStore.java:170` |

`<log-bucket>` is each bucket named in `CloudTrailLogBucket` and `S3AccessLogBucket`
(`cloudtrail_log_bucket`, `s3_access_log_bucket`). These are the AWS counterpart of the GCP
`log_sink_bucket` role: object content is readable in the audit-log buckets the operator names
and nowhere else.

**Not granted, on purpose.** `s3:GetObject` and `s3:ListBucket` on any data bucket, or on `*`
(the Reader sees bucket names, configuration and audit logs, never data content), and
`athena:GetQueryResults`.

**Limits to know.**
- LookupEvents returns management events only. S3 object reads are data events and do not appear
  there; they reach DLAP through the trail files in the named trail bucket (the
  `S3_TRAIL_FILES` transport).
- A log bucket encrypted with SSE-KMS also needs `kms:Decrypt` on its key. The Reader does not
  grant it; add it in the key policy for the Reader role, scoped to that key, when the trail or
  access log bucket uses a customer managed key. SSE-S3 needs nothing more.
- In a Lake Formation account, Glue `GetTables` also needs the Lake Formation `DESCRIBE`
  permission on the databases. Grant it in Lake Formation; it is not an IAM action.

### Live check

```bash
deployment/iam/aws/reader/live-check.sh <reader-role-arn> [<trail-bucket>] [<access-log-bucket>]
```

It runs the IAM policy simulator (`iam:SimulatePrincipalPolicy`, nothing is called or changed):
every account-scope action must be allowed, `s3:ListBucket` and `s3:GetObject` must be allowed on
each log bucket named on the command line, and none of 15 write or content probes (for example
`athena:GetQueryResults`, `s3:PutObject`, `cloudtrail:StopLogging`, `iam:PassRole`) may be, nor
`s3:ListBucket` or `s3:GetObject` on a bucket that is not a named log bucket. Service control
policies are not simulated. `live-check.sh --self-test` proves the script offline.

## Azure

### Apply

```bash
cd deployment/iam/azure/reader
terraform init
terraform apply -var subscription_id=<subscription> -var principal_object_id=<managed-identity-object-id>
```

The custom role `DLAP Reader` is narrower than the built-in Reader, which grants `*/read`. Assign
it to the managed identity of the pilot VM; the module creates no secret.

### Permissions (subscription scope)

| Action | Why | Call site |
|---|---|---|
| `Microsoft.Storage/storageAccounts/read` | list storage accounts | `AzureCoverageRestClient.java:76` |
| `Microsoft.Insights/diagnosticSettings/read` | whether blob diagnostic settings send StorageRead | `AzureCoverageRestClient.java:91` |

Today only the COV-01 coverage collector calls Azure. The VM crawler logs "not implemented", the
Data Factory and Synapse job lists are offline stubs and `AzureLogAnalyzer` reads nothing, so none
of them needs a permission yet. Their real implementations add their actions to the trace.

Live check (manual until the Azure family ships):
`az role definition list --name "DLAP Reader" --query "[0].permissions"` must show exactly the two
actions and empty `dataActions`.

## Databricks

### Apply

```bash
export DATABRICKS_HOST=https://<workspace-host>   # plus your OAuth variables
cd deployment/iam/databricks/reader
terraform init
terraform apply -var 'catalogs=["main"]'
```

The module grants with `databricks_grant`, which adds one principal's privileges and leaves every
other grant on the catalog alone. Issue a token for the service principal through your normal
process and set `CLOUD_PROVIDER_UNITY_TOKEN`; the module creates none.

### Privileges (on each catalog, inherited by its schemas)

| Privilege | Why | Call site |
|---|---|---|
| `USE_CATALOG` | list catalogs (only catalogs the caller owns or uses are returned) | `UnityCatalogResourceCrawler.java:219` |
| `USE_SCHEMA` | list schemas (same rule) | `UnityCatalogResourceCrawler.java:233` |
| `BROWSE` | table metadata without data access | `UnityCatalogResourceCrawler.java:248` |

**Not granted, on purpose:** `SELECT`, `MODIFY` and every other data privilege.

**Known gap.** The tables list returns BROWSE-only tables only when the request sets
`include_browse=true`, and the crawler does not send it yet. Until it does, the crawler lists only
the tables the service principal owns. The fix belongs in the crawler, not in the role: `SELECT`
would let the Reader read table content.

Live check (manual): `databricks grants get-effective catalog <name>` for the service principal
must show exactly the three privileges.

## What the Reader never holds

- Any create, update, delete, set-policy or put permission, on any cloud.
- Object or table content outside the audit-log export (GCP) or the named log buckets (AWS), or
  anywhere (Azure, Databricks).
- Key or token creation for itself: every module creates the identity without a credential.

## Actor roles (archive and delete)

Each cloud has one Actor trace, `deployment/iam/<cloud>/actor/permissions.yaml`, written like the
Reader traces: every permission names its scope, the executors that need it (`delete` is
da-autodelete, `archive` is da-autoarchival) and every call site as `file:line` plus the call text.
An Actor holds only the calls the executors make on their approved paths:

- the **native lifecycle rule** on a bucket, a prefix, a container or a table (read the rules,
  write the merged rules, remove DLAP's rule), which needs no object permission at all;
- optionally, the **single-object** delete or archive that no rule can isolate (one S3 object, one
  GCS object, one Azure blob), behind a switch that is off by default.

Apply each module once per executor. Nothing is granted on `*`, a project, a subscription or a
wildcard bucket: the roles are granted on the resources the operator names and nowhere else.

| Cloud | Deploy with | Names the resources with |
|---|---|---|
| AWS | `deployment/iam/aws/actor/actor-role.cfn.yaml` (CloudFormation, `AWS::LanguageExtensions`) or `deployment/iam/aws/actor/` (Terraform) | `BucketNames` / `buckets`; `ObjectActions` / `object_actions` |
| GCP | `deployment/iam/gcp/actor/` (Terraform) | `buckets`, `datasets`; `object_actions` |
| Azure | `deployment/iam/azure/actor/` (Terraform) | `storage_account_ids`, `container_ids`; `object_actions` |

`ActorRoleTraceabilityTest` (da-admin-service, runs with `:da-admin-service:test`) fails when:

- a call site no longer exists or its line no longer holds the quoted call (the message names the
  line the call moved to);
- a permission's `executors` differ from the modules of its call sites;
- an object read, write, delete or restore is on any scope but the object scope (`bucket_objects`
  on AWS and GCP, `container` on Azure), a permission holds a wildcard, or a content permission
  that no executor may hold (`s3:DeleteObjectVersion`, `s3:GetObjectVersion`,
  `s3:BypassGovernanceRetention`, `bigquery.tables.getData`, `bigquery.tables.updateData`, Azure
  `listKeys/action`), or that one executor may never hold (Azure `blobs/read` and `blobs/write` for
  the delete Actor), is granted to it or missing from `never_grant`;
- a Terraform statement or grant names a resource other than the named buckets, datasets,
  accounts or containers (validated variables refuse ARNs and wildcards), or a module grants on a
  project, folder or organization;
- the CloudFormation template grants a different action set for either executor, or a statement
  names a resource other than the loop's `${BucketName}`;
- a file of da-autodelete or da-autoarchival imports a cloud client and is neither cited nor
  listed (`no_permission_sources`, `unreachable_sources`, `out_of_role_sources`);
- this guide does not name an Actor permission.

Each trace also records the calls that need no grant, each with its line and reason:
`tolerated_denials` (a size probe before a delete; a denial is logged and the delete goes on, so
the delete Actor never reads content), `unreachable_calls` (the RDS, DynamoDB, Cosmos, Azure SQL
and BigQuery-table branches of the engine delete, which `DispositionPlanner` never sends there,
and status methods nothing calls) and `out_of_role_calls` (calls made with a credential Azure RBAC
does not govern; none are left since the archive executor signs in with `DefaultAzureCredential`).

A `never_grant` entry binds every executor unless it names `executors`; then it binds those, and its
`exception` says who else holds it and why. The Azure archive Actor's `blobs/read` and `blobs/write`
are the single documented exception (below); the test fails on any other.

### AWS Actor

```bash
aws cloudformation deploy --stack-name dlap-delete-actor \
  --template-file deployment/iam/aws/actor/actor-role.cfn.yaml \
  --capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
  --parameter-overrides RoleName=dlap-delete-actor Executor=delete BucketNames=bucket-a,bucket-b \
    TrustedPrincipalArn=<dlap-runtime-role-arn> ExternalId=<external-id>
```

Terraform: `terraform apply -var executor=delete -var role_name=dlap-delete-actor
-var 'buckets=["bucket-a","bucket-b"]' -var trusted_principal_arn=<dlap-runtime-role-arn>
-var external_id=<external-id>`. Repeat with `Executor=archive` for da-autoarchival. The template
writes one inline policy per bucket (the AWS limit of 10,240 characters of inline policy per role
holds about 25 buckets; use a second role beyond that).

| Action | Scope | Resource | Executors | Why | Call sites |
|---|---|---|---|---|---|
| `s3:GetLifecycleConfiguration` | bucket | `arn:<partition>:s3:::<bucket>` | delete, archive | read the rules to keep the owner's | `S3LifecycleRuleApplier.java:220`, `S3LifecycleRuleProvider.java:305` |
| `s3:PutLifecycleConfiguration` | bucket | same | delete, archive | write the merged rules; also covers DeleteBucketLifecycle (Q-697) | `S3LifecycleRuleApplier.java:144,176,178`, `S3LifecycleRuleProvider.java:289,319` |
| `s3:GetBucketVersioning` | bucket | same | delete | versioned buckets are refused (Q-656, Q-697) | `S3LifecycleRuleApplier.java:202` |
| `s3:GetBucketLocation` | bucket | same | delete | client in the bucket's region | `S3LifecycleRuleApplier.java:335` |
| `s3:ListBucket` | bucket | same | archive | first keys of the scope for the plan preview | `S3LifecycleRuleProvider.java:209` |
| `s3:DeleteObject` | bucket_objects | `arn:<partition>:s3:::<bucket>/*` | delete | one approved object (engine delete) | `AwsCloudDeletionService.java:275` |
| `s3:GetObject` | bucket_objects | same | archive | HeadObject and the CopyObject source | `AwsS3StorageService.java:131,149` |
| `s3:PutObject` | bucket_objects | same | archive | the CopyObject target with the archive class | `AwsS3StorageService.java:149` |
| `s3:RestoreObject` | bucket_objects | same | archive | restore one archived object | `AwsS3StorageService.java:203` |

The bucket_objects statement exists only with `ObjectActions=true` (`object_actions = true`). The
delete Actor never holds `s3:GetObject`: its size probe (`AwsCloudDeletionService.java:256`)
tolerates a denial. A bucket encrypted with SSE-KMS also needs `kms:Decrypt` and
`kms:GenerateDataKey` on its key for the archive copy; grant them in the key policy for the
archive Actor only.

**Executor credentials today.** da-autodelete and da-autoarchival use the AWS SDK default chain,
and neither ships the `software.amazon.awssdk:sts` module, so a web identity (IRSA) or a profile
`role_arn` cannot be resolved inside them yet. Until it is added (an open question for the
executor owners), give the executor host the Actor role as its instance profile (the module's
`actor_instance_profile`, created when no trusted principal is set), or run the executor with
temporary credentials of the Actor role in its environment.

### GCP Actor

```bash
cd deployment/iam/gcp/actor
terraform init
terraform apply -var executor=delete -var project_id=<project> -var service_account_id=dlap-delete-actor \
  -var 'buckets=["bucket-a"]' -var 'datasets=["sales"]'
```

| Permission | Scope | Granted on | Executors | Why | Call sites |
|---|---|---|---|---|---|
| `storage.buckets.get` | bucket | each named bucket | delete, archive | lifecycle rules, metageneration, versioning | `GcsLifecycleRuleApplier.java:378,393`, `GcsLifecycleRuleProvider.java:263` |
| `storage.buckets.update` | bucket | same | delete, archive | write the rules with a metageneration precondition | `GcsLifecycleRuleApplier.java:388`, `GcsLifecycleRuleProvider.java:204,246` |
| `storage.objects.list` | bucket | same | archive | first objects of the scope for the plan preview | `GcsLifecycleRuleProvider.java:147` |
| `storage.objects.delete` | bucket_objects | same, only with `object_actions` | delete, archive | one approved object; the archive rewrite overwrites the object | `GcpCloudDeletionService.java:199`, `GcsCloudStorageService.java:158,227` |
| `storage.objects.get` | bucket_objects | same | archive | the rewrite source | `GcsCloudStorageService.java:146,158,210,227` |
| `storage.objects.create` | bucket_objects | same | archive | the rewrite target | `GcsCloudStorageService.java:158,227` |
| `bigquery.tables.get` | dataset | each named dataset | delete | the table's current expiration | `BigQueryTableExpirationApplier.java:122,131` |
| `bigquery.tables.update` | dataset | same | delete | set the table expiration | `BigQueryTableExpirationApplier.java:135` |

Roles: `dlap<Executor>ActorBucket`, `dlap<Executor>ActorObjects` and `dlap<Executor>ActorDataset`,
project custom roles granted with `google_storage_bucket_iam_member` and
`google_bigquery_dataset_iam_member` only. A project custom role can be granted only on resources
of its own project, so apply the module once per project.

### Azure Actor

```bash
cd deployment/iam/azure/actor
terraform init
terraform apply -var executor=delete -var subscription_id=<subscription> -var 'role_name=DLAP Delete Actor' \
  -var 'storage_account_ids=["<account-arm-id>"]' -var principal_object_id=<managed-identity-object-id>
```

| Action | Scope | Assigned on | Executors | Why | Call sites |
|---|---|---|---|---|---|
| `Microsoft.Storage/storageAccounts/blobServices/read` | storage_account | each named account | delete | read the blob service properties, to refuse a native delete rule on a versioned account | `AzureBlobLifecycleRuleApplier.java:225` |
| `Microsoft.Storage/storageAccounts/managementPolicies/read` | storage_account | each named account | delete | read the policy to keep the owner's rules | `AzureBlobLifecycleRuleApplier.java:242` |
| `Microsoft.Storage/storageAccounts/managementPolicies/write` | storage_account | same | delete | write the merged policy | `AzureBlobLifecycleRuleApplier.java:163,196` |
| `Microsoft.Storage/storageAccounts/managementPolicies/delete` | storage_account | same | delete | remove the policy when DLAP's rule was the last | `AzureBlobLifecycleRuleApplier.java:194` |
| `Microsoft.Storage/storageAccounts/blobServices/containers/blobs/delete` (data action) | container | each named container, only with `object_actions` | delete | one approved blob | `AzureCloudDeletionService.java:202` |

The role definitions are assignable only to the named accounts and containers. The account list
is the same as `dlap.autodelete.native-rule.azure-storage-account-ids`.

`Microsoft.Storage/storageAccounts/blobServices/read` (Q-743, Q-694) lets the delete executor read
the account's blob service properties and refuse a native delete rule on a versioned account. It
is a control-plane read of account settings on the `storage_account` scope, so it is assigned on
the named accounts only, like the management policy actions, and it reads no blob content.
Without it every Azure container rule waits with unknown versioning once dry run is off.

#### Azure archive Actor

The archive Actor is a second instance of the same module with `executor=archive` and its own
`role_name` (it must contain "archive"). It is never merged with the delete Actor: the two share no
permission and no role definition. It holds no account action, because da-autoarchival renders the
management policy and never writes it, and it signs in with `DefaultAzureCredential` (EXEC-01), so
Azure RBAC governs every call it makes.

```bash
terraform apply -var executor=archive -var subscription_id=<subscription> -var 'role_name=DLAP Archive Actor' \
  -var object_actions=true -var 'container_ids=["<account-arm-id>/blobServices/default/containers/<name>"]' \
  -var principal_object_id=<managed-identity-object-id>
```

| Action | Scope | Assigned on | Executors | Why | Call sites |
|---|---|---|---|---|---|
| `Microsoft.Storage/storageAccounts/blobServices/containers/blobs/read` (data action) | container | each named container, only with `object_actions` | archive | List Blobs (the plan preview of a rule's scope) and Get Blob Properties (the size after a tier change) | `AzureManagementPolicyRuleProvider.java:124`, `AzureCloudStorageService.java:148` |
| `Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write` (data action) | container | each named container, only with `object_actions`, with the `Blob.Write.Tier` condition | archive | Set Blob Tier: archive one blob, and restore it to Hot | `AzureCloudStorageService.java:147` (archive), `AzureCloudStorageService.java:199` (restore) |

Azure authorizes Set Blob Tier only with `blobs/write` and List Blobs and Get Blob Properties only
with `blobs/read` (Microsoft, [Permissions for Blob service operations](https://learn.microsoft.com/en-us/rest/api/storageservices/authorize-with-azure-active-directory#permissions-for-blob-service-operations)).
There is no narrower action, so the archive Actor holds exactly these two, on the named containers
only. They are the single documented exception to `never_grant`, which still forbids both to the
delete Actor (it never reads or writes a blob; its size probe tolerates a denial).

How far each one is narrowed:

- **`blobs/write` is narrowed to the tier change.** Azure role assignment conditions (ABAC) have a
  suboperation for it, `Blob.Write.Tier` ("Sets the access tier on a blob",
  [Actions and attributes for Azure role assignment conditions for Azure Blob Storage](https://learn.microsoft.com/en-us/azure/storage/blobs/storage-auth-abac-attributes#sets-the-access-tier-on-a-blob)).
  The trace carries the condition and Terraform puts it on every archive container assignment
  (`condition_version = "2.0"`): every blob write that is not `Blob.Write.Tier` meets an
  expression that is never true, so the assignment allows no Put Blob, Put Block, metadata,
  properties, lease or snapshot write. The condition follows the documented format
  ([condition format](https://learn.microsoft.com/en-us/azure/role-based-access-control/conditions-format));
  its first live apply is a founder check.
- **`blobs/read` cannot be narrowed.** List Blobs has its own suboperation (`Blob.List`), but Get
  Blob Properties and Get Blob (the content) are one operation, "Read a blob", with no suboperation
  between them. A condition that kept List only would break the size read, so the read is granted
  on the named containers and nothing else.

**Which path, and when.** Recommended: the native lifecycle management policy path. For a prefix
or a whole-container scope (plan kind `PREFIX` or `BUCKET`) DLAP renders the account's management
policy rule (`RULE_RENDERED`) and a person or pipeline applies it with their own
`Microsoft.Storage/storageAccounts/managementPolicies/write` on that account (the delete Actor's
action, never the archive Actor's). Azure then tiers the blobs itself, so the archive Actor needs
no blob data access at all: apply it with `object_actions=false` and it holds no role. The plan
preview lists blobs only when `dlap.autoarchival.azure.account-endpoint` is set. Without the read
grant, leave that endpoint unset: the preview then says the listing is unavailable, where a set
endpoint and a denied List Blobs would fail the plan with the provider's error.

Per-blob tiering (`object_actions = true`) is for what no rule can isolate: a single-object scope
(plan kind `OBJECT`, a direct tier change of one approved blob), the restore of an archived blob
back to Hot, and the listing preview. It needs both data actions above, on the named containers.

The self-check expects `blobs/read` and `blobs/write` of the archive executor on each container
configured under `dlap.permission-check.azure.executors.archive` with `object-actions=true`, and
nothing with `object-actions=false`. It sees the role's data actions, not the assignment
condition; read the condition with
`az role assignment list --assignee <principal> --scope <container-arm-id> --query "[].condition"`.
One identity that holds both Actors fails the check: each Actor's permissions are extra for the
other. Give the delete and archive executors their own identities (per-service identities belong
to DEP-03).

## The permission self-check

`GET /api/v1/permission-check` (all clouds) and `GET /api/v1/permission-check/{aws|gcp|azure}` on
da-admin-service, ADMIN only. For every resource configured below, the provider's dry-run API is
asked which permissions the Actor identity holds, and the answer is compared with the committed
Actor trace (the jar carries a copy; `dlap.permission-check.trace-dir` points at a checkout of
`deployment/iam` instead):

- **missing**: a permission the trace gives the executor on that scope that the identity lacks;
- **extra**: anything else held there: another executor's permission, a `never_grant` entry, any
  object permission while object actions are off, any grant on a bucket nobody named (AWS), or any
  effective-permission pattern beyond the trace (Azure, for example `Microsoft.Storage/*`);
- **readerHeld**: permissions of the same cloud's Reader trace, listed apart for a shared identity.

**Whose permissions are read.** Every report carries `identityChecked` (the identity), `identityBasis`
and `identityNote` (one sentence), so a `PASS` is never read as a statement about an identity that
was not asked (Q-727):

- `NAMED_ROLE` (AWS): the simulator evaluates the Actor role ARN you configured, whoever makes the
  call. `identityChecked` is that ARN.
- `CALLER` (GCP, Azure): the provider API answers for the identity that makes the call. The report
  checks the calling identity: the service account or managed identity da-admin-service runs as,
  or the token in `access-token-file`. `identityChecked` says which of the two it was. It is the
  Actor only when that identity is the Actor's. Nothing is impersonated: da-admin-service never
  asks for a token of the Actor (`iam.serviceAccounts.getAccessToken` or an Azure on-behalf-of
  grant), because that would hand the admin service the Actor's power. To test the Actor
  itself, run the check as the Actor or give it that Actor's token file. Per-service identities
  (one identity per service, so da-admin-service can run as its own and the executors as the
  Actor) belong to the Helm package DEP-03; on the single-VM pilot every service shares one
  identity, so the check reports that shared identity (its Reader permissions appear as `readerHeld`).
- Nothing configured: `identityChecked` is `null`, `identityBasis` and `identityNote` still say
  what a configured check would answer for.

The status is `PASS`, `FAIL`, `ERROR` (a resource could not be probed; the reason is in the
target) or `NOT_CONFIGURED`. Nothing is read from or written to a bucket.

| Cloud | API | Identity checked (`identityBasis`) | da-admin-service needs |
|---|---|---|---|
| AWS | `iam:SimulatePrincipalPolicy` on each Actor role ARN, per named bucket, per object probe key, on a bucket nobody named, and the `account` never-grant entries on `*` | the configured role ARN (`NAMED_ROLE`) | `iam:SimulatePrincipalPolicy` on the Actor role ARNs; credentials from the environment or the instance profile |
| GCP | Cloud Storage `testIamPermissions` on each named bucket, BigQuery `tables.testIamPermissions` on one named table per dataset | the caller (`CALLER`): the attached service account, or the account of `access-token-file` | run as the Actor account, or a token of it in `access-token-file` |
| Azure | `Microsoft.Authorization/permissions` (api-version 2022-04-01) on each named account and container | the caller (`CALLER`): the managed identity, or the identity of `access-token-file` | run as the Actor identity, or a token of it in `access-token-file` |

Settings (environment variables bind by Spring's relaxed rules):

```properties
dlap.permission-check.aws.executors.delete.role-arn=arn:aws:iam::<account>:role/dlap-delete-actor
dlap.permission-check.aws.executors.delete.buckets=bucket-a,bucket-b
dlap.permission-check.aws.executors.delete.object-actions=false
dlap.permission-check.gcp.executors.delete.buckets=bucket-a
dlap.permission-check.gcp.executors.delete.bigquery-tables=<project>.sales.<table>
dlap.permission-check.gcp.access-token-file=/run/secrets/gcp-actor-token
dlap.permission-check.azure.executors.delete.storage-account-ids=<account-arm-id>
dlap.permission-check.azure.executors.delete.container-ids=<account-arm-id>/blobServices/default/containers/<name>
dlap.permission-check.azure.executors.archive.container-ids=<account-arm-id>/blobServices/default/containers/<name>
dlap.permission-check.azure.executors.archive.object-actions=true
dlap.permission-check.azure.managed-identity-client-id=<client-id>
```

Limits: the AWS simulator does not apply service control policies or bucket policies; GCP and
Azure answer for the caller only, so a token of another identity tests that identity. Emulators do
not implement these APIs: the tests use stand-ins that answer in the documented response shapes,
and the first real answer per cloud is a founder check on a live account.

## DLAP's runtime identity and cross-account trust

DLAP runs with one identity of its own per cloud (on AWS: the IRSA role of the pod or the instance
profile role of the pilot VM). That identity holds no customer permission. It assumes the
customer's Reader and Actor roles, and each customer role trusts exactly that identity with an
external id (Q-703).

**The runtime identity's policy** (AWS). It may assume only the customer's DLAP roles:

```hcl
data "aws_iam_policy_document" "dlap_runtime_assume" {
  statement {
    sid     = "AssumeCustomerDlapRoles"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    resources = [
      "arn:aws:iam::<customer-account>:role/dlap-reader",
      "arn:aws:iam::<customer-account>:role/dlap-delete-actor",
      "arn:aws:iam::<customer-account>:role/dlap-archive-actor",
    ]
  }
}
```

```yaml
# CloudFormation, on DLAP's runtime role (IRSA or instance profile role)
- PolicyName: dlap-assume-customer-roles
  PolicyDocument:
    Version: "2012-10-17"
    Statement:
      - Sid: AssumeCustomerDlapRoles
        Effect: Allow
        Action: sts:AssumeRole
        Resource:
          - arn:aws:iam::<customer-account>:role/dlap-reader
          - arn:aws:iam::<customer-account>:role/dlap-delete-actor
          - arn:aws:iam::<customer-account>:role/dlap-archive-actor
```

For IRSA, the runtime role's own trust policy allows `sts:AssumeRoleWithWebIdentity` from the
cluster's OIDC provider for the DLAP service account only (conditions
`<oidc-provider>:sub = system:serviceaccount:<namespace>:<service-account>` and
`<oidc-provider>:aud = sts.amazonaws.com`). For the pilot VM, the instance profile role trusts
`ec2.amazonaws.com`.

**The customer roles' trust.** Every Reader and Actor module takes `trusted_principal_arn` (the
runtime role ARN) and `external_id` (CloudFormation `TrustedPrincipalArn` and `ExternalId`), and
needs an external id with it (CloudFormation refuses a principal without one through a Rules
assertion; the Terraform modules only warn, Q-729, and a `StringEquals` condition on an empty
`sts:ExternalId` never matches, so the role stays unassumable). The trust statement, with the
`sts:ExternalId` condition:

```json
{
  "Effect": "Allow",
  "Principal": { "AWS": "arn:aws:iam::<dlap-account>:role/<dlap-runtime-role>" },
  "Action": "sts:AssumeRole",
  "Condition": { "StringEquals": { "sts:ExternalId": "<external-id>" } }
}
```

External id guidance:

- One external id per customer, generated by DLAP's operator (for example 32 random characters),
  never chosen by the customer and never reused across customers. It stops a confused deputy:
  another customer who learns the role ARN cannot make DLAP assume it.
- It is not a cryptographic secret, but keep it out of logs and tickets; the modules mark it
  `sensitive` (Terraform) and `NoEcho` (CloudFormation).
- Trust the runtime role ARN, never the account root (`arn:aws:iam::<dlap-account>:root`), so only
  DLAP's own identity can assume the role.
- Keep the maximum session at one hour (the modules set `max_session_duration = 3600`).

The runtime identity's `sts:AssumeRole` is traced like an Actor permission, in
`deployment/iam/aws/actor/permissions.yaml` under `runtime_identity`:

| Permission | Held by | Resources | Trust on the Actor role | Call sites |
|---|---|---|---|---|
| `sts:AssumeRole` | DLAP's runtime identity (IRSA role or instance profile role), never an Actor | each named Actor role ARN, never `*` | the runtime role ARN (never the account root) plus an `sts:ExternalId` condition, session at most 3600 seconds | `ActorCredentials.java:175,191,193` in da-autodelete and in da-autoarchival (EXEC-01): a role ARN plus an external id |

Which services can assume today: da-autodelete and da-autoarchival read a role ARN through
`dlap.<service>.aws.credentials-ref` (with `external-id`, `sts-endpoint` and `sts-region`) and
assume the Actor role with session name `dlap-autodelete` or `dlap-autoarchival` (EXEC-01). An
empty reference keeps the SDK default chain, so the instance profile or an EKS web identity token
(IRSA) is the Actor. da-logingest assumes its Reader role the same way (ADR-0110, `SdkS3LogStore.java`).
da-discovery and da-admin-service use the default chain or the instance profile directly.

GCP and Azure need no assume step: grant the Reader and Actor roles directly to the service
account (GCP, attached to the VM or through Workload Identity) or the managed identity (Azure).
