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

# DLAP fixture corpus

Golden inputs and expected results for the log layer. This file also marks the corpus root on the
classpath (`FixtureCatalog.fromClasspath()` looks for it), so keep it here.

Module overview, tasks and the rules in full: `da-log-fixtures/README.md`.

## Layout

```
dlap-fixtures/
  <family>/                           cloudtrail, gcpaudit, azactivity, ...
    catalog.yaml                      documented operations and variants per envelope and version
    NOTICE.md                         attribution; "all cases synthetic" when nothing is third-party
    <envelope>/<version>/<case>/      positive cases
      input.json | input.json.gz | input.jsonl | input.row.json | ...
      expected.json                   what a correct parser reports
      meta.yaml                       provenance, see below
    negative/<envelope>/<version>/<case>/    inputs that must be quarantined or rejected
```

`negative` is reserved and cannot be an envelope id. Quote version ids in YAML (`"1.10"`), or
YAML turns them into numbers and drops the trailing zero.

## meta.yaml

| Key | Required | Meaning |
|---|---|---|
| `origin` | yes | `synthetic-from-doc`, `synthetic-from-proto`, `public-dataset`, `emulator-captured`, `own-account-redacted` or `partner-redacted` |
| `source_url`, `doc_date` | by origin | the documentation or proto the case was written from, and its date (ISO). Capture date for captured origins |
| `repo`, `commit` | by origin | `owner/name` and the full 40-character SHA of the repository a public case came from |
| `license` | yes | `synthetic`, `Apache-2.0`, `MIT` or `BSD-2-Clause` |
| `covers` | yes | documented `service:operation` pairs the case exercises (`[]` allowed on negative cases) |
| `variant_ids` | yes | documented variant ids the case exercises (`[]` if none) |
| `expected_outcome` | yes | for example `PARSED`, `QUARANTINED:UNKNOWN_MAJOR_VERSION` |
| `anonymised` | yes | true when identifiers were anonymised or redacted; required true for public datasets and captures |
| `notes` | no | free text for reviewers |

Citation by origin: synthetic origins need `source_url` and `doc_date`; `public-dataset` needs
`repo` and `commit`; `emulator-captured` needs either pair; the two redacted origins need `doc_date`.

Never add: vendor documentation samples, Elastic content (Elastic License 2.0), flaws.cloud data
(no licence), Grafana Loki content (AGPL) or an unredacted capture.
