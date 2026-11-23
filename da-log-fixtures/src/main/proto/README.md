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

# Vendored Google API protos (test support)

These `.proto` files are unmodified copies of Google's published API definitions. They are used
only to check fixture shapes: `protoc` compiles them into a descriptor set
(`dlap-fixtures-proto/googleapis.desc` in this module's jar, Gradle task
`generateProto`), and the proto round-trip test reads every confirmed GCP Cloud Audit fixture through
it (`com.dlap.fixtures.gen.gcpaudit.GcpAuditProtoRoundTripTest`). No Java classes are generated
from them, so they never clash with the Google client libraries on a consumer's class path.

| | |
|---|---|
| Repository | https://github.com/googleapis/googleapis |
| Commit | `ba591f1d7fdedc610639425448756cd8a64adc0f` (2026-10-19), retrieved 2026-10-20 |
| Licence | Apache-2.0; every file carries Google's Apache-2.0 header ("Copyright 2025 Google LLC" or older) |
| NOTICE | the repository has no NOTICE file |

Files: `google/cloud/audit/audit_log.proto`, `google/cloud/audit/bigquery_audit_metadata.proto`,
`google/cloud/bigquery/logging/v1/audit_data.proto`, `google/logging/v2/log_entry.proto`, and
their import closure (`google/api/*`, `google/iam/v1/*`, `google/logging/type/*`,
`google/rpc/status.proto`, `google/rpc/context/attribute_context.proto`, `google/type/expr.proto`), and `google/iam/v1/logging/audit_data.proto` (the IAM policy delta some services put in `serviceData`).
The `google/protobuf/*` well-known types come from the protobuf-java jar.

To refresh: download the same paths from a newer commit, update the commit above and
`GcpAuditCaseWriter.GOOGLEAPIS_COMMIT`, run `gw :da-log-fixtures:build`, regenerate the cases
(`GcpAuditGenerator write`), and regenerate da-logingest's copy of the AuditData descriptors,
`dlap/log-formats/gcpaudit/bigquery-auditdata-v1.descriptors.json`, with
`GoogleApisDescriptors <that file>` (`GcpAuditDescriptorSyncTest` in da-logingest fails while the
copies differ).
