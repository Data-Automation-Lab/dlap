/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.logingest.demo;

import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.service.ILogIngestService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Run-once seeder that injects ~50 synthetic GCP audit-log events
 * through {@link ILogIngestService} so the entire log-event spine can
 * be exercised end-to-end without real GCP credentials.
 *
 * <p>Exercises ~90% of the runtime pipeline:
 * {@code synthetic event → DlapLogEvent → Kafka(dlap.log.event.normalized) →
 * DiscoveryLogEventProcessor → typed lifecycle topic →
 * AssetLifecycleEventListener (catalog state update) →
 * LifecyclePolicyConsumer (policy match) →
 * dlap.policy.action.event → da-autoarchival/da-autodelete consumers}.
 *
 * <p>The 10% gap is cloud-side parsing fidelity - real GCP audit-log
 * JSON has edge cases this synthetic generator doesn't reproduce. M3
 * acceptance verification with real GCP creds closes the gap.
 *
 * <p>Enabled only when {@code dlap.logingest.demo-seed.enabled=true};
 * runs once on startup and exits cleanly. Safe to leave the property
 * off in production - the bean is not instantiated.
 *
 * <p>Customize the spread via:
 *
 * <ul>
 *   <li>{@code dlap.logingest.demo-seed.event-count} - how many events
 *       to emit (default 50).</li>
 *   <li>{@code dlap.logingest.demo-seed.project} - synthetic GCP
 *       project id (default {@code dlap-demo-project}).</li>
 * </ul>
 */
@Component
@Profile({"demo", "dev"})
@ConditionalOnProperty(name = "dlap.logingest.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DemoEventSeeder implements ApplicationRunner {

    private static final List<String> ASSETS_GCS = List.of(
            "//storage.googleapis.com/projects/_/buckets/customer-pii-2024",
            "//storage.googleapis.com/projects/_/buckets/transactions-archive",
            "//storage.googleapis.com/projects/_/buckets/marketing-events",
            "//storage.googleapis.com/projects/_/buckets/cold-backups-2022"
    );
    private static final List<String> ASSETS_BIGQUERY = List.of(
            "projects/dlap-demo-project/datasets/analytics/tables/orders",
            "projects/dlap-demo-project/datasets/analytics/tables/customers_pii",
            "projects/dlap-demo-project/datasets/finance/tables/transactions",
            "projects/dlap-demo-project/datasets/staging/tables/event_clicks"
    );
    private static final List<String> METHODS_CREATE = List.of(
            "storage.buckets.create", "google.cloud.bigquery.v2.TableService.InsertTable"
    );
    private static final List<String> METHODS_ACCESS = List.of(
            "storage.objects.get", "storage.objects.list",
            "google.cloud.bigquery.v2.JobService.InsertJob"
    );
    private static final List<String> METHODS_UPDATE = List.of(
            "storage.buckets.update", "google.cloud.bigquery.v2.TableService.PatchTable"
    );
    private static final List<String> METHODS_DELETE = List.of(
            "storage.buckets.delete", "google.cloud.bigquery.v2.TableService.DeleteTable"
    );
    private static final List<String> METHODS_PERMISSION = List.of(
            "storage.buckets.setIamPolicy", "google.cloud.bigquery.v2.DatasetService.PatchDataset"
    );
    private static final List<String> PRINCIPALS = List.of(
            "alice@dlap-demo.example.com",
            "bob@dlap-demo.example.com",
            "etl-pipeline@dlap-demo-project.iam.gserviceaccount.com",
            "compliance-bot@dlap-demo-project.iam.gserviceaccount.com"
    );

    private final ILogIngestService logIngestService;

    @org.springframework.beans.factory.annotation.Value(
            "${dlap.logingest.demo-seed.event-count:50}")
    private int eventCount;

    @org.springframework.beans.factory.annotation.Value(
            "${dlap.logingest.demo-seed.project:dlap-demo-project}")
    private String demoProjectId;

    @Override
    public void run(ApplicationArguments args) {
        log.info("DemoEventSeeder enabled - injecting {} synthetic GCP audit events into {} via ILogIngestService",
                eventCount, demoProjectId);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int sent = 0;
        for (int i = 0; i < eventCount; i++) {
            try {
                // 30% CREATE, 35% ACCESS, 15% UPDATE, 10% DELETE, 10% PERMISSION
                int roll = rnd.nextInt(100);
                IngestRequestDTO dto;
                if (roll < 30) {
                    dto = build("CREATE", pick(METHODS_CREATE, rnd), pickAsset(rnd), i, rnd);
                } else if (roll < 65) {
                    dto = build("ACCESS", pick(METHODS_ACCESS, rnd), pickAsset(rnd), i, rnd);
                } else if (roll < 80) {
                    dto = build("UPDATE", pick(METHODS_UPDATE, rnd), pickAsset(rnd), i, rnd);
                } else if (roll < 90) {
                    dto = build("DELETE", pick(METHODS_DELETE, rnd), pickAsset(rnd), i, rnd);
                } else {
                    dto = build("PERMISSION", pick(METHODS_PERMISSION, rnd), pickAsset(rnd), i, rnd);
                }
                logIngestService.ingest(dto);
                sent++;
            } catch (Exception e) {
                log.warn("Demo seed event {} failed: {}", i, e.getMessage());
            }
        }
        log.info("DemoEventSeeder finished. Sent {} synthetic events; watch Kafka topic "
                + "dlap.log.event.normalized + the six typed lifecycle topics + "
                + "dlap.policy.action.event for the cascade.", sent);
    }

    private IngestRequestDTO build(String changeType, String operation, String assetUri,
                                   int index, ThreadLocalRandom rnd) {
        boolean isBigQuery = assetUri.startsWith("projects/");
        String resourceKind = isBigQuery
                ? "bigquery_resource"
                : "gcs_bucket";

        String principal = pick(PRINCIPALS, rnd);
        boolean isServiceAccount = principal.contains(".iam.gserviceaccount.com");

        Map<String, Object> raw = new HashMap<>();
        raw.put("synthetic", true);
        raw.put("operationIndex", index);
        raw.put("methodName", operation);
        raw.put("seededBy", "DemoEventSeeder");

        // Spread events across the last ~48h so policy match-clauses with
        // lastAccessedAt: olderThan duration windows have something to bite on.
        Instant eventTime = Instant.now().minus(rnd.nextLong(0, 48), ChronoUnit.HOURS);

        return IngestRequestDTO.builder()
                .sourceProvider("GCP")
                .sourceService(isBigQuery
                        ? "bigquery.googleapis.com"
                        : "storage.googleapis.com")
                .operation(operation)
                .changeType(changeType)
                .resultStatus("SUCCESS")
                .eventTime(eventTime)
                .principalId(principal)
                .principalType(isServiceAccount ? "SERVICE_ACCOUNT" : "USER")
                .principalDisplayName(principal)
                .principalAccountId(demoProjectId)
                .targetResourceKind(resourceKind)
                .targetResourceId(assetUri)
                .targetResourceName(assetUri.substring(assetUri.lastIndexOf('/') + 1))
                .targetProject(demoProjectId)
                .targetRegion(pick(List.of("us-central1", "us-east1", "europe-west1"), rnd))
                .callerIp("203.0.113." + rnd.nextInt(1, 250))
                .userAgent("dlap-demo-seeder/1.0")
                .requestId(UUID.randomUUID().toString())
                .rawEvent(raw)
                .build();
    }

    private String pickAsset(ThreadLocalRandom rnd) {
        // 50/50 GCS / BigQuery for coverage of both bestiary kinds.
        return rnd.nextBoolean() ? pick(ASSETS_GCS, rnd) : pick(ASSETS_BIGQUERY, rnd);
    }

    private static <T> T pick(List<T> list, ThreadLocalRandom rnd) {
        return list.get(rnd.nextInt(list.size()));
    }
}
