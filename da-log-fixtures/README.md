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

# da-log-fixtures

The golden fixture corpus for the log layer, and the gates that keep it honest. Every parser,
contract and drift test reads its inputs from here, so producers and consumers test against the
same bytes.

**Test support only.** Other modules may use it from `testImplementation` or `testRuntimeOnly`,
never from `implementation`, `api` or `runtimeOnly`. The `verifyFixturesTestOnly` task and
`scripts/ci-guards/post-test/fixtures.sh` fail the build otherwise.

## Use it from a test

```kotlin
// <module>/build.gradle.kts
dependencies {
    testImplementation(project(":da-log-fixtures"))
    // testImplementation(testFixtures(project(":da-log-fixtures"))) resolves too; the test-fixtures
    // variant is empty and only passes the main variant (the corpus) through.
}
```

```java
FixtureCatalog corpus = FixtureCatalog.fromClasspath();           // fails on any malformed file
for (FixtureCase c : corpus.cases("cloudtrail")) {                 // families: corpus.familyNames()
    String input = c.inputText();                                  // gunzips input.*.gz
    JsonNode expected = c.expectedTree();
    // parse input, compare with expected, assert c.meta().expectedOutcome()
}
```

Cases of a family whose `catalog.yaml` says `selftest: true` exist only to test the tooling
(`FixtureCase#selftest()`); skip them in real parser walks.

## What is in the corpus

`src/main/resources/dlap-fixtures/`. Layout, the `meta.yaml` schema and the licence rules are in
`dlap-fixtures/README.md`. In short:

```
dlap-fixtures/<family>/catalog.yaml                     documented operations and variants
dlap-fixtures/<family>/NOTICE.md                        attribution
dlap-fixtures/<family>/<envelope>/<version>/<case>/     input.*, expected.json, meta.yaml
dlap-fixtures/<family>/negative/<envelope>/<version>/<case>/
```

## Gradle tasks

All of them run in `check`, so `gw :da-log-fixtures:build` runs every gate.

| Task | What it enforces |
|---|---|
| `verifyFixtureProvenance` | Every case has a valid `meta.yaml`; the licence is `synthetic`, `Apache-2.0`, `MIT` or `BSD-2-Clause`; origin, licence and citation agree; known never-vendored sources are blocked; datasets and captures are `anonymised: true`; every family has a `catalog.yaml` and a `NOTICE.md` that attributes each third-party repository. Report: `build/reports/fixtures/provenance.txt`. |
| `fixtureCoverage` | Scores each (family, envelope, version) in `catalog.yaml` and fails any entry marked `supported: true` below `-Pdlap.fixtures.minScore` (default `0.99`). Reports: `build/reports/fixture-coverage/COVERAGE.md` and `coverage.json`. |
| `verifyFixturesTestOnly` | No module depends on this one outside a test configuration. |
| `aggregateFixtureNotice` | Joins the per-family `NOTICE.md` files into `META-INF/dlap-fixtures-NOTICE.md` of the jar, so no shared NOTICE file is edited. |

CI runs the same checks as `scripts/ci-guards/post-test/fixtures.sh`.

## Scoring

Per (family, envelope, version) documented in a `catalog.yaml`:

```
score = documented operations covered / documented operations
      x documented variants covered   / documented variants
      x drift kill ratio              (n/a until the drift mutator exists; counts as 1)
```

- Operations are covered by positive cases (`covers: [service:operation]`).
- Variants are covered by positive and negative cases (`variant_ids`).
- A case that claims something the catalog does not list is reported as undeclared and does not
  raise the score. A case in an envelope or version the catalog does not declare fails the gate.
- An entry is gated only once it is `supported: true`, which a parser package sets when it claims
  the version.

## Add a family or a case

1. Create `dlap-fixtures/<family>/catalog.yaml`. List every operation and variant the vendor
   documents for each envelope and version. Quote version ids (`"1.10"`).
2. Create `dlap-fixtures/<family>/NOTICE.md`. Say "all cases synthetic" when nothing is third-party.
3. Add case directories with `input.*`, `expected.json` and `meta.yaml`.
4. Run `gw :da-log-fixtures:build` and read `build/reports/fixture-coverage/COVERAGE.md`.

## Rules

- Never add vendor documentation samples, Elastic content (Elastic License 2.0), flaws.cloud data
  (no licence), Grafana Loki content (AGPL) or an unredacted capture. Write synthetic cases from
  the documented field lists and cite the page and its date.
- Public-dataset cases keep the dataset's licence, name its repository and commit, are anonymised,
  and appear in the family `NOTICE.md`.
- Java sources here are Apache-2.0 headed like the rest of the repository; corpus data files
  carry their provenance in `meta.yaml` instead.

## Build notes

The root `build.gradle.kts` applies its microservice conventions to every subproject not on its
exclusion list. This module's `build.gradle.kts` removes what it inherited (Spring Boot starters,
Kafka, JPA, Lombok) and disables `bootJar`, so the jar and its test classpath stay small. If the
root exclusion list gains `da-log-fixtures`, that code becomes a no-op.
