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

# Scale, soak and backfill resilience (E2E-02)

`run.sh` drives synthetic load through the real log layer and records what it measures. It does not
claim a number it has not measured: a figure the run did not produce is written as `not measured`.

| Command | What it does | Where it runs |
|---|---|---|
| `bash e2e-tests/perf/run.sh lite` | 200k CloudTrail, 50k GCP and 50k Azure records, then the backfill resume test | a developer machine, under the host-wide harness lock |
| `bash e2e-tests/perf/run.sh hosted` | 1M CloudTrail, 250k GCP and 250k Azure records (51 minutes of load at the measured 486.2 events/s, 2.22 GB of Postgres), then the backfill resume test; `DLAP_PERF_FLAWS=1` adds the flaws.cloud check | the `DLAP Nightly Soak` workflow (the schedule and the default of a manual run), sized for a hosted runner (CI-03, Q-780; the arithmetic is in `perf-lib.sh`) |
| `bash e2e-tests/perf/run.sh full` | 10M CloudTrail, 2M GCP and 2M Azure records (8 hours and 20.7 GB of Postgres at the measured rate), then the backfill resume test; `DLAP_PERF_FLAWS=1` adds the flaws.cloud check | a manual dispatch of the `DLAP Nightly Soak` workflow on a larger runner (the `runner` input); it does not fit a hosted job |
| `bash e2e-tests/perf/run.sh backfill` | only the backfill resume test | either |
| `bash e2e-tests/perf/run.sh flaws` | only the flaws.cloud check | the workflow, or a self-test with `DLAP_FLAWS_URL` pointing at a stand-in archive |
| `bash e2e-tests/perf/test-perf-lib.sh` | offline self-test of `perf-lib.sh` and the argument handling | anywhere; CI runs it in the script self-tests job |

Add `--no-build` to skip the Gradle jar build and `--keep` to leave the stack up.

## What is driven

The spine of `cloud-pipeline-e2e.sh` (postgresql, kafka, keycloak, da-logingest, da-discovery, da-catalog) on
the registry pipeline, and an S3 emulator (`docker-compose.perf.yml`):

- **CloudTrail** is written as gzip trail files into the emulator, split over several accounts, and read by one
  `S3_TRAIL_FILES` stream per account, the production path. The streams of different bindings are polled by
  different scheduler threads, so the compose override raises `spring.task.scheduling.pool.size` (default 1) and
  the database pool (default 10, which starved a first run).
- **GCP and Azure** are posted to the REST channel (`POST /api/v1/logingest/events`) from many threads, as
  normalized events. The log layer has no offline raw transport for them (the GCS sink cannot be pointed at an
  emulator and there is no Azure transport), so the raw record rides along as `rawEvent`. The fixture replay
  seeder is not used: it is single threaded and blocks application start, so the CloudTrail streams could not run
  beside it (a first run stored about 60 events a second that way). A da-logingest test (`ScaleRecordsParseTest`)
  parses the raw GCP entries with the real parser, so the generator's GCP records are valid audit records even
  though the run does not read them from a bucket.

The generators are `da-log-fixtures` `gen/scale/**`: seeded, deterministic, JDK only. The same seed, counts and
end time give the same records. Every name, account and address is invented.

## What is recorded

Throughput of stored events (end to end, median and best window), consumer lag of every group on
`dlap.log.event.normalized`, Postgres growth, Kafka partition skew, quarantine rate, dead-letter messages. The
results file is `results/<mode>-<date>.md` with the samples CSV beside it (`DLAP_PERF_RESULTS_DIR` moves both).
Named checks at the end of the file decide the exit status.

## Backfill resume

A backfill job reads a trail dated five days back (another account, so the stream's one day lookback never reads
it) at a throttled rate. `da-logingest` is killed with SIGKILL after a quarter of the events are stored and is
started again. The test then compares the table with the generator's list of event ids: the count, the count of
distinct `event_id` and `native_event_id`, and the set difference must show no record lost and none stored twice.
It also reports how many messages the job wrote to the topic, because an event published and not yet committed
when the process dies can be published again on the re-read.

## flaws.cloud

Only the nightly workflow downloads the public flaws.cloud CloudTrail archive, at run time, from the URL in
`flaws-cloud.pin`. The file is checked against the pinned size and SHA-256 before it is extracted, and nothing of
it is committed (no licence is stated for it, decision D8). The run places its files under trail keys, reads them
with a backfill job and requires a quarantine rate under 1 percent. While the pin says `UNPINNED` the workflow
allows unpinned runs (only the pinned size is enforced, not the SHA-256) and prints the computed checksum on
every run, until the founder pins it.

## Files

| File | Purpose |
|---|---|
| `run.sh` | the harness |
| `perf-lib.sh` | pure helpers (profiles, Kafka output parsing, rates, the verified download) |
| `test-perf-lib.sh` | offline self-test |
| `docker-compose.perf.yml` | the S3 emulator and the da-logingest settings |
| `flaws-cloud.pin` | URL, size and checksum pins of the flaws.cloud archive |
| `results/` | committed results of lite runs |
