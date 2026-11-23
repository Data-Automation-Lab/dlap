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

# Example: a log-sources bindings file

A bindings file tells da-logingest which accounts and projects to read, through which
transports, and which format version each one delivers. Reference:
[docs/guides/log-sources.md](../../../docs/guides/log-sources.md).

## Use it

1. Save the file below as `log-sources.yml` and edit ids, scopes and locations.
2. Point the service at it, either way:
   - `DLAP_LOGINGEST_SOURCES_FILE=/path/to/log-sources.yml`, or
   - mount it at `/etc/dlap/log-sources.yml` (the default path).
3. Select the registry pipeline: `DLAP_LOGINGEST_PIPELINE=registry`, and keep the legacy sources
   off (`DLAP_LOGINGEST_AWS_ENABLED=false`, `dlap.logingest.gcp.enabled=false`).
4. Start the service. Under the `prod` profile any configuration error stops startup with the
   rule and path of each error; under `dev` the broken bindings are logged and skipped.
5. Check `GET /actuator/info` (`logSources.configHash` and each binding's `state`).

A binding runs only when the build has a parser for its source kind and a transport for its
stream. The cloud families (`AWS_CLOUDTRAIL`, `GCP_CLOUD_AUDIT`, ...) and their transports arrive
with the wave-3 family packages; until then this file validates only under a profile that warns
(`dev`), and the bindings are reported as `UNKNOWN_SOURCE_KIND` and `UNKNOWN_TRANSPORT`. The
built-in REST and fixture-replay bindings need no file.

## The file: one customer, mixed versions

Acme runs three AWS accounts and two GCP projects. Account 1 sits on the organization trail and
auto-detects the CloudTrail version; account 2 is still polled through LookupEvents and is
pinned to `1.08`, quarantining anything else; project p1 exports through a BigQuery sink with
the legacy AuditData payload; project p2 uses the Logging API with auto-detection.

```yaml
dlap:
  logingest:
    config-mode: FILE
    config-validation: auto          # fail under prod, warn otherwise
    defaults:
      on-mismatch: PARSE_WITH_DETECTED
      on-unknown-major: QUARANTINE   # the only accepted value
      persist-raw: QUARANTINE_ONLY

    sources:
      - id: aws-org
        kind: AWS_CLOUDTRAIL
        overlap: PT20M               # re-read window; CloudTrail delivers in about 5 minutes
        bindings:
          - id: acme-aws-111111111111
            scope: { account-id: "111111111111", regions: [us-east-1, eu-west-1] }
            stream:
              transport: S3_TRAIL_FILES
              location:
                bucket: acme-org-trail
                prefix: "AWSLogs/o-abc123/111111111111/CloudTrail/"
            format: { envelope: s3-file, version: auto }
            credentials-ref: ACME_TRAIL_READER_ROLE_ARN   # an environment variable name

          - id: acme-aws-222222222222
            scope: { account-id: "222222222222", regions: [us-east-1] }
            stream: { transport: LOOKUP_EVENTS }
            format: { envelope: lookup-events, version: "1.08", on-mismatch: QUARANTINE }
            operation-overrides:
              - { service: s3.amazonaws.com, operation: PutObject, change-type: UPDATE, category: DATA_WRITE }

          - id: acme-aws-333333333333
            enabled: false             # validated and mirrored, never polled
            scope: { account-id: "333333333333" }
            stream: { transport: LOOKUP_EVENTS }
            format: { envelope: lookup-events }

      - id: gcp
        kind: GCP_CLOUD_AUDIT
        overlap: PT10M
        bindings:
          - id: acme-gcp-p1
            scope: { project-id: acme-prod-p1 }
            stream: { transport: BQ_SINK, location: { dataset: audit_sink } }
            format: { envelope: bq-sink-row, version: bq-auditdata-legacy }
          - id: acme-gcp-p2
            scope: { project-id: acme-prod-p2 }
            stream: { transport: LOGGING_API }
            format: { envelope: api-proto, version: auto }
            field-overrides:
              principal.display-name: /protoPayload/authenticationInfo/principalSubject
            filters:
              exclude-services: [ logging.googleapis.com ]
```

Notes:

- Quote account ids and version pins (`"111111111111"`, `"1.08"`): unquoted YAML numbers lose
  leading zeros, and `1.10` would read as `1.1`.
- Never put a secret in the file. `credentials-ref` names an environment variable (or is an IAM
  role ARN); the validator refuses anything else and never prints the value.
- Reading one account through two streams (the organization trail and a per-account trail) is an
  `OVERLAPPING_STREAMS` error unless one of them declares `dedup: cross-stream`; event-id dedup
  keeps the events single either way, the rule only makes the double polling deliberate.
- Binding ids appear in `/actuator/info` and on every event (`binding_id`); choose ids you are
  willing to show.
