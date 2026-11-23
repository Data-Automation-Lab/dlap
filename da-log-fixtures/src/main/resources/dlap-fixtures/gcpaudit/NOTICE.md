# Third-party notices: gcpaudit seed cases

Cases in this directory whose `meta.yaml` says `origin: public-dataset` are anonymised excerpts of the
repositories below. Each case names its repository, commit, licence and date in `meta.yaml`;
`seeds.yaml` pins the same values and lists every selected record, so the import can be repeated.
Identifiers (project ids and numbers, service account and user e-mail addresses, IP addresses,
organization and access policy numbers) are replaced by `com.dlap.fixtures.anon.Anonymiser`; JSON is
re-indented and the description lines that precede a log in the source file are dropped. Those are the
only changes.

## GoogleCloudPlatform/professional-services

- Repository: https://github.com/GoogleCloudPlatform/professional-services
- Path: `examples/audit-log-examples`
- Commit: d86f53e2dccb7bef1f4755e43bd92d8fd5d2d060 (2026-10-05), retrieved 2026-10-19
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Copyright 2019 Google LLC
- The repository has no NOTICE file.

What the data is: sample Cloud Audit Log entries published in 2021 and 2022, so they predate
`authorizationInfo[].permissionType` and are filed under `auditlog-2021`.

## googleapis/google-cloudevents

- Repository: https://github.com/googleapis/google-cloudevents
- Path: `testdata/google/events/cloud/audit/v1`
- Commit: 5427368e99996f3185f45d6eb444f8f7c1cd6e2d (2026-10-15), retrieved 2026-10-19
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- The repository has no NOTICE file. Its LICENSE carries no copyright holder line, so none is stated here.

What the data is: the CloudEvents project's BigQuery `jobservice.jobcompleted` LogEntry in the legacy v1
`AuditData` shape (`bq-auditdata-legacy`).

## Not imported

The panther-labs/panther-analysis `gcp_audit_rules` samples are not used. They carry Panther's
normalisation fields (`p_any_*`, `p_udm`, `at_sign_type`, lower-cased keys), so they are not provider
shaped records.

## Cases written for GCP-01

Every other case is synthetic and carries no third-party content. Cases with `origin:
synthetic-from-proto` are written by `com.dlap.fixtures.gen.gcpaudit.GcpAuditCaseBook` from the field
lists of the googleapis protos vendored in `da-log-fixtures/src/main/proto` (Apache-2.0, see the
README there) and confirmed by `GcpAuditProtoRoundTripTest`; cases with `origin: synthetic-from-doc`
(invalid inputs, unknown fields and every `bq-sink-row` case) are hand-written from the Cloud Logging
reference and export pages they cite. All identifiers are invented.
