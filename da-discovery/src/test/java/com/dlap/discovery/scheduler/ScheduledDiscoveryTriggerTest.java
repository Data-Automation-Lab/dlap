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

package com.dlap.discovery.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.discovery.client.admin.AdminConnectionDetails;
import com.dlap.discovery.client.admin.AdminServiceException;
import com.dlap.discovery.client.admin.IAdminServiceClient;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.config.dto.ConnectionDiscoveryConfigDTO;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.test.util.FakeConfigService;
import com.dlap.discovery.test.util.FakeJobService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The scheduled crawler must be DRIVEN by the persisted ConnectionDiscoveryConfig (disabled
 * connections skipped, the per-connection interval respected, the scope applied) and, since
 * DISC-01, it must actually RUN the job it creates: attach the default crawler executable
 * after the last save, submit that instance, and report a job that could not start as
 * FAILED instead of leaving it in CREATED.
 *
 * <p>Uses hand-rolled fakes (no Mockito): the Mockito ByteBuddy agent is unstable on this
 * JDK, and mocking the {@code final} AdminConnectionDetails would force the inline mock
 * maker. Time comes from a fixed {@link Clock} (rule R6), never from the wall clock.
 */
class ScheduledDiscoveryTriggerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private FakeJobService jobService;
    private FakeConfigService configService;
    private ScheduledDiscoveryTrigger trigger;

    @BeforeEach
    void setUp() {
        jobService = new FakeJobService();
        configService = new FakeConfigService();
        IAdminServiceClient adminClient =
                id -> new AdminConnectionDetails("acct-1", CloudProvider.GCP, id);
        trigger = newTrigger(adminClient);
    }

    private ScheduledDiscoveryTrigger newTrigger(IAdminServiceClient adminClient) {
        ScheduledDiscoveryTrigger t = new ScheduledDiscoveryTrigger(jobService, adminClient, configService,
                null, null, null, List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(t, "connectionIdsCsv", "conn-1");
        ReflectionTestUtils.setField(t, "jobNamePrefix", "scheduled-scan");
        return t;
    }

    @Test
    void disabledConnection_isSkipped_noJobCreated() {
        configService.config = ConnectionDiscoveryConfigDTO.builder()
                .cloudConnectionId("conn-1").isEnabled(false).build();

        trigger.runScheduledScans();

        assertThat(jobService.createCount.get()).isZero();
        assertThat(configService.recordedFor).isNull();
    }

    @Test
    void enabledDueConnection_firesScopedScan_andStampsLastScan() {
        configService.config = ConnectionDiscoveryConfigDTO.builder()
                .cloudConnectionId("conn-1").isEnabled(true)
                .scanIntervalHours(24)                     // never scanned -> due
                .resourceTypesToInclude(List.of("GCS_BUCKET")).build();

        trigger.runScheduledScans();

        assertThat(jobService.executed).isNotNull();
        assertThat(jobService.executed.getParameters())
                .containsEntry("resourceTypesToCrawl", List.of("GCS_BUCKET"));
        assertThat(configService.recordedFor).isEqualTo("conn-1");
    }

    @Test
    void startedJob_hasTheDefaultCrawlerExecutableAttached() {
        configService.config = null;

        trigger.runScheduledScans();

        // The fake refuses to execute a job with no executable, exactly like
        // DiscoveryJobService.executeJob, so reaching executed proves the wiring.
        assertThat(jobService.executed).isNotNull();
        assertThat(jobService.executed.getExecutable()).isNotNull();
        assertThat(jobService.executed.getAccountId()).isEqualTo("acct-1");
        assertThat(jobService.executed.getCloudProvider()).isEqualTo(CloudProvider.GCP);
        assertThat(jobService.executed.getJobName()).startsWith("scheduled-scan-");
    }

    @Test
    void executesTheInstanceReturnedBySave_notTheDetachedOne() {
        jobService.saveReturnsCopy = true;

        trigger.runScheduledScans();

        // The executable is transient, so it must be attached to the saved instance.
        assertThat(jobService.executed).isNotNull();
        assertThat(jobService.executed).isSameAs(jobService.saved.get(jobService.saved.size() - 1));
        assertThat(jobService.executed).isNotSameAs(jobService.created.get(0));
        assertThat(jobService.executed.getParameters()).containsEntry("triggerSource", "scheduled");
    }

    @Test
    void jobThatCannotStart_isPersistedAsFailed_andConnectionIsNotStamped() {
        configService.config = ConnectionDiscoveryConfigDTO.builder()
                .cloudConnectionId("conn-1").isEnabled(true).scanIntervalHours(24).build();
        jobService.executeFailure = new IllegalStateException("No executor registered for mode: DEFAULT");

        trigger.runScheduledScans();

        assertThat(jobService.executed).isNull();
        var job = jobService.created.get(0);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("No executor registered"));
        // Not stamped, so the next tick retries instead of waiting out the interval.
        assertThat(configService.recordedFor).isNull();
    }

    @Test
    void connectionScannedWithinInterval_isSkipped() {
        configService.config = ConnectionDiscoveryConfigDTO.builder()
                .cloudConnectionId("conn-1").isEnabled(true)
                .scanIntervalHours(24)
                .lastScanStartedAt(NOW.minus(Duration.ofHours(1))) // 1h < 24h
                .build();

        trigger.runScheduledScans();

        assertThat(jobService.createCount.get()).isZero();
    }

    @Test
    void dueDecisionAndStamp_useTheInjectedClock() {
        configService.config = ConnectionDiscoveryConfigDTO.builder()
                .cloudConnectionId("conn-1").isEnabled(true)
                .scanIntervalHours(24)
                .lastScanStartedAt(NOW.minus(Duration.ofHours(24))) // exactly one interval ago -> due
                .build();

        trigger.runScheduledScans();

        assertThat(jobService.createCount.get()).isEqualTo(1);
        assertThat(configService.recordedAt).isEqualTo(NOW);
    }

    @Test
    void noConfig_fallsBackToFullScan() {
        configService.config = null; // getConnectionDiscoveryConfig -> empty

        trigger.runScheduledScans();

        assertThat(jobService.executed).isNotNull();
        assertThat(jobService.executed.getParameters()).containsEntry("query_scanType", "FULL");
        assertThat(jobService.executed.getParameters()).doesNotContainKey("resourceTypesToCrawl");
    }

    @Test
    void connectionTheAdminServiceRejects_isCountedFailed_andOthersStillRun() {
        ScheduledDiscoveryTrigger t = newTrigger(id -> {
            if ("conn-bad".equals(id)) {
                throw new AdminServiceException(AdminServiceException.Reason.NOT_FOUND, "Cloud connection not found");
            }
            return new AdminConnectionDetails("acct-" + id, CloudProvider.AWS, id);
        });
        ReflectionTestUtils.setField(t, "connectionIdsCsv", "conn-bad, conn-good");

        t.runScheduledScans();

        assertThat(jobService.createCount.get()).isEqualTo(1);
        assertThat(jobService.executed.getAccountId()).isEqualTo("acct-conn-good");
        assertThat(jobService.executed.getCloudProvider()).isEqualTo(CloudProvider.AWS);
    }

    @Test
    void noConnectionIds_doesNothing() {
        ReflectionTestUtils.setField(trigger, "connectionIdsCsv", " ");

        trigger.runScheduledScans();

        assertThat(jobService.createCount.get()).isZero();
    }
}
