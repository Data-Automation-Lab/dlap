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

package com.dlap.logingest.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.LogHealthIntegrationBase;
import com.dlap.logingest.model.BindingState;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.SourceBindingEntity;
import com.dlap.logingest.repository.SourceBindingRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Q-558 wiring, on the running service with PostgreSQL (Testcontainers; requires Docker): the
 * backfill meters are beans of the real context, the status gauges read the real {@code backfill_job}
 * table, and the series appear in the registry under the documented names.
 */
class BackfillMetricsWiringTest extends LogHealthIntegrationBase {

    @Autowired
    private BackfillMetrics backfillMetrics;

    @Autowired
    private BackfillJobs jobs;

    @Autowired
    private SourceBindingRepository bindings;

    @Test
    void theStatusGaugesAreRegisteredForEveryStatus() {
        for (BackfillStatus status : BackfillStatus.values()) {
            assertThat(meters.find(BackfillMetrics.JOBS).tag("status", status.name()).gauge())
                    .as("gauge for %s", status).isNotNull();
        }
    }

    @Test
    void theCountersAndTheTimerAppearInTheRegistryOnFirstUse() {
        backfillMetrics.batch("wiring-binding", 3, 1);
        backfillMetrics.objectFailed("wiring-binding", BackfillMetrics.REASON_READ);
        backfillMetrics.jobEnded("wiring-binding", BackfillStatus.SUCCEEDED, Instant.parse("2026-10-20T10:00:00Z"),
                Instant.parse("2026-10-20T10:01:00Z"));

        assertThat(meters.get(BackfillMetrics.RECORDS_READ).tag("binding", "wiring-binding").counter().count())
                .isEqualTo(3);
        assertThat(meters.get(BackfillMetrics.RECORDS_QUARANTINED).tag("binding", "wiring-binding").counter().count())
                .isEqualTo(1);
        assertThat(meters.get(BackfillMetrics.OBJECTS_FAILED).tag("reason", "read").counter().count()).isEqualTo(1);
        assertThat(meters.get(BackfillMetrics.JOB_DURATION).tag("status", "SUCCEEDED").timer().count()).isEqualTo(1);
    }

    @Test
    void aJobInTheTableShowsInTheGaugeAfterTheCacheExpires() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("DELETE FROM dlap_logingest.backfill_job");
        if (!bindings.existsById("wiring-b")) {
            SourceBindingEntity b = new SourceBindingEntity();
            b.setBindingId("wiring-b");
            b.setSourceId("aws");
            b.setSourceKind("AWS_CLOUDTRAIL");
            b.setConfigOrigin(ConfigOrigin.FILE);
            b.setState(BindingState.ACTIVE);
            b.setEnabled(true);
            b.setScope(Map.of());
            b.setEffectiveConfig(Map.of());
            b.setConfigHash("0".repeat(64));
            b.setFirstSyncedAt(Instant.parse("2026-10-20T10:00:00Z"));
            b.setLastSyncedAt(Instant.parse("2026-10-20T10:00:00Z"));
            bindings.save(b);
        }
        jobs.insert(id, "wiring-b", "s", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"),
                "ops", Instant.parse("2026-10-20T10:00:00Z"));

        // The gauge caches for BackfillMetrics.GAUGE_TTL_SECONDS; poll past that.
        double pending = 0;
        for (int i = 0; i < 30 && pending < 1; i++) {
            pending = meters.get(BackfillMetrics.JOBS).tag("status", "PENDING").gauge().value();
            if (pending < 1) {
                Thread.sleep(1000);
            }
        }
        assertThat(pending).isEqualTo(1.0);
    }

    @Test
    void aRunningJobShowsItsHeartbeatAgeInTheRealContext() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("DELETE FROM dlap_logingest.backfill_job");
        if (!bindings.existsById("wiring-hb")) {
            SourceBindingEntity b = new SourceBindingEntity();
            b.setBindingId("wiring-hb");
            b.setSourceId("aws");
            b.setSourceKind("AWS_CLOUDTRAIL");
            b.setConfigOrigin(ConfigOrigin.FILE);
            b.setState(BindingState.ACTIVE);
            b.setEnabled(true);
            b.setScope(Map.of());
            b.setEffectiveConfig(Map.of());
            b.setConfigHash("0".repeat(64));
            b.setFirstSyncedAt(Instant.parse("2026-10-20T10:00:00Z"));
            b.setLastSyncedAt(Instant.parse("2026-10-20T10:00:00Z"));
            bindings.save(b);
        }
        jobs.insert(id, "wiring-hb", "s", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"),
                "ops", Instant.parse("2026-10-20T10:00:00Z"));
        jobs.markRunning(id, Instant.parse("2026-10-20T10:01:00Z"));
        jobs.saveProgress(id, BackfillProgress.EMPTY.withHeartbeat(Instant.parse("2026-10-20T10:02:00Z")));

        // The status gauges find the binding and register its heartbeat gauge; the gauge then reads the row.
        double age = 0;
        for (int i = 0; i < 30 && age < 1; i++) {
            meters.get(BackfillMetrics.JOBS).tag("status", "RUNNING").gauge().value();
            var gauge = meters.find(BackfillMetrics.HEARTBEAT_AGE).tag("binding", "wiring-hb").gauge();
            age = gauge == null ? 0 : gauge.value();
            if (age < 1) {
                Thread.sleep(1000);
            }
        }
        assertThat(age).as("seconds since 2026-10-20T10:02:00Z on the real clock").isGreaterThan(3600.0);
    }
}
