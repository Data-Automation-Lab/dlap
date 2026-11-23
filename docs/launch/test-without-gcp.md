# Test the log-event spine without cloud credentials

You can push events through DLAP's log-event spine without connecting a
cloud account. This guide covers the three offline entry points, what each one
proves, and what it cannot prove. It replaces an earlier version that claimed
the synthetic path covers about 90 percent of the runtime pipeline with
"HIGH" accuracy. No such measurement exists, and the claim is withdrawn.

## What this exercises

```
synthetic event
   -> ILogIngestService.ingest
        entry points: DemoEventSeeder, FixtureReplaySeeder,
                      POST /api/v1/logingest/events
   -> DlapLogEvent published to dlap.log.event.normalized
   -> DiscoveryLogEventProcessor (da-discovery) routes by changeType
   -> typed topics dlap.discovery.asset.{discovered, modified, deleted,
                                         accessed, permission-changed, archived}
   -> da-catalog AssetLifecycleEventListener: asset upserts
   -> da-policyengine LifecyclePolicyConsumer: policy match,
        publishes dlap.policies.actions
   -> da-autoarchival / da-autodelete consumers
```

Source: `DemoEventSeeder.java`, `FixtureReplaySeeder.java`,
`IngestController.java:51,68`, `DlapConstants.KafkaTopic`
(`dlap.log.event.normalized` at line 248, the typed topics at lines 230-235,
`dlap.policies.actions` at line 213).

## What this does not exercise

- The cloud log sources. `GcpLogSource` (Cloud Logging `entries.list`) and
  `CloudTrailLogSource` (CloudTrail `LookupEvents`) are not started: both are off
  by default and need real credentials. Their paging, filter syntax, throttling
  behaviour and watermark resume are untested by this path.
- Real audit-log payload shapes. The seeder generates flat `IngestRequestDTO`
  records, not provider-native audit JSON, so parsing fidelity is not tested.
- Credential flows, such as Application Default Credentials or workload
  identity.
- Real classification. The quickstart labels with a mock provider (see
  [../getting-started.md](../getting-started.md), step 4).
- Real cloud actions. Deletion is dry-run in the quickstart
  (`DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE`, `docker-compose.microservices.yml:381`).

Treat a green run here as "the internal wiring works for these synthetic
events", not as evidence about any cloud.

## Option A: built-in demo seeder (recommended for first-time setup)

1. **Start the stack** with seeding turned on. The dev compose passes
   `DLAP_LOGINGEST_DEMO_SEED_ENABLED` through to `da-logingest`
   (`docker-compose.microservices.yml:415`):

   ```bash
   cd deployment/environments/dev
   DLAP_LOGINGEST_DEMO_SEED_ENABLED=true \
   docker compose -f docker-compose.yml -f docker-compose.microservices.yml up -d --build
   ```

   `DemoEventSeeder` is an `ApplicationRunner` active only under the `demo` or
   `dev` profile when `dlap.logingest.demo-seed.enabled=true`. It emits 50 events
   by default (`dlap.logingest.demo-seed.event-count`; the compose file does not
   pass that setting, so add it to the `da-logingest` environment block to
   change it) against 4 GCS bucket URIs and 4 BigQuery table URIs. The operation
   mix is random with weights of 30 percent create, 35 access, 15 update, 10
   delete and 10 permission change (`DemoEventSeeder.java:128-141`).

2. **Wait about 30 seconds** for the seeder to finish and the downstream
   consumers to drain. Watch:

   ```bash
   docker logs dlap-da-logingest --tail 50 | grep DemoEventSeeder
   docker logs dlap-da-discovery --tail 50 | grep DiscoveryLogEventProcessor
   docker logs dlap-da-catalog --tail 50 | grep AssetLifecycleEventListener
   docker logs dlap-da-policyengine --tail 50 | grep LifecyclePolicyConsumer
   ```

   Expected: `DiscoveryLogEventProcessor` logs a routing per event, and the
   catalog listener logs asset upserts for the seeded URIs.

3. **Verify in the UI**:
   - Open `http://localhost:3000` and sign in with a seeded dev user (see
     [../getting-started.md](../getting-started.md)).
   - **Data Estate** (`/data-catalog`) should list the seeded assets.
   - **Smart Label** shows labels, but the quickstart provider is a mock: its
     labels are random placeholders.

4. **Create a policy in the editor** (`/policy-editor`):

   ```yaml
   apiVersion: dlap/v1
   kind: Policy
   metadata:
     id: demo-archive-on-access
     name: "Archive GCS buckets on access (demo)"
     owner: "demo@example.com"
     enabled: true
   spec:
     triggers:
       - type: event
         lifecycle: accessed
     match:
       cloudProvider: GCP
       assetType:
         anyOf: [GCS_BUCKET]
     action:
       type: archive
       dryRun: true
       parameters:
         targetStorageClass: ARCHIVE
         auditNote: "Demo policy"
   ```

   Save it as ENABLED. The next ACCESSED event publishes an action to
   `dlap.policies.actions`, and `da-autoarchival` picks it up. The field
   reference is [../specs/policy-dsl.md](../specs/policy-dsl.md). Only some action
   types have a live consumer; the "Implementation status" table there is
   authoritative.

5. **Fire more events.** Restart `da-logingest` with seeding enabled (it runs
   once per start), or use Option B or C.

## Option B: hand-crafted events over HTTP

POST your own `IngestRequestDTO` to `/api/v1/logingest/events`. The caller needs a
Keycloak token carrying `ROLE_ADMIN` or `ROLE_DATA_ENGINEER`
(`IngestController.java:68-69`); a service-account token with only
`ROLE_SERVICE_ACCOUNT` is rejected. Required fields: `sourceProvider`,
`operation`, `changeType`, `eventTime` (`IngestRequestDTO.java:52-66`).

```json
{
  "sourceProvider": "GCP",
  "sourceService": "storage.googleapis.com",
  "operation": "storage.buckets.delete",
  "changeType": "DELETE",
  "resultStatus": "SUCCESS",
  "eventTime": "2026-04-21T10:00:00Z",
  "principalId": "alice@example.com",
  "principalType": "USER",
  "targetResourceKind": "gcs_bucket",
  "targetResourceId": "//storage.googleapis.com/projects/_/buckets/test-bucket",
  "targetResourceName": "test-bucket",
  "targetProject": "demo-project",
  "targetRegion": "us-central1"
}
```

A successful call returns HTTP 202 with the assigned `eventId`.

## Option C: offline fixture replay (no token)

`FixtureReplaySeeder` replays per-cloud JSON arrays of `IngestRequestDTO`
in-process, bypassing the REST layer and its authorization checks
(`FixtureReplaySeeder.java`). Enable it with
`dlap.logingest.replay.enabled=true` and `dlap.logingest.replay.dir=<path>`;
`dlap.logingest.replay.provider=<prefix>` selects one cloud. The dev compose does
not wire these variables; the e2e compose does
(`e2e-tests/docker-compose.e2e.yml:29-31`). The fixtures are in
`e2e-tests/fixtures/replay/` (`aws-events.json`, `azure-events.json`,
`gcp-events.json`; see its README) and are driven by
`e2e-tests/cloud-pipeline-e2e.sh`.

## Verifying without the UI

Tail the topics with the console consumer inside the Kafka container:

```bash
docker exec dlap-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic dlap.log.event.normalized \
  --from-beginning --max-messages 5

docker exec dlap-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic dlap.discovery.asset.discovered \
  --from-beginning --max-messages 5

docker exec dlap-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic dlap.policies.actions \
  --from-beginning --max-messages 5
```

If the console consumer cannot reach the broker on `localhost:9092` from inside
the container, check the advertised listeners in
`deployment/environments/dev/docker-compose.yml` (services reach Kafka at
`kafka:29092`).

## What still requires a real cloud account

- `GcpLogSource.pollProject` against the real Cloud Logging API: paging,
  filter syntax, watermark resume across restarts.
- `CloudTrailLogSource` against real CloudTrail `LookupEvents`: per-region
  polling, throttling, and the fact that it returns management events only.
- Real audit-log JSON from production services.
- Workload identity or other credential flows.
- API quota and throttling behaviour.

To pull from a real GCP project, set `GOOGLE_APPLICATION_CREDENTIALS`,
`DLAP_LOGINGEST_GCP_ENABLED=true` and the project list
(`dlap.logingest.gcp.projects`), and run under the `gcp` or `prod` profile
(`GcpLogSource.java`, class annotations). The synthetic path keeps working
alongside it, and both publish to the same `dlap.log.event.normalized` topic.
