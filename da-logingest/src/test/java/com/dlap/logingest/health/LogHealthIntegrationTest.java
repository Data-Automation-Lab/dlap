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

package com.dlap.logingest.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.LogHealthIntegrationBase;
import com.dlap.logingest.metrics.LogHealthFixtures;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.proto.log.v1.BindingState;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.ReadCoverage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * LOG-03 acceptance, health: the status of each binding goes to its topic with real counts, cursors
 * and read coverage; the health API and the drift report answer for ADMIN; the {@code logParsers}
 * component of {@code /actuator/health} reports the startup self-test. PostgreSQL by Testcontainers
 * (needs Docker); Kafka mocked.
 */
class LogHealthIntegrationTest extends LogHealthIntegrationBase {

    private static final String BINDING = "dlap-rest-aws";

    @Autowired
    private BindingStatusPublisher statusPublisher;

    @Autowired
    private HealthSnapshotService snapshots;

    @Autowired
    private ParserSelfTest selfTest;

    @Autowired
    private RegistryIngestService ingestService;

    private static String event(String id, String change, String result, String extra) {
        return "{\"sourceProvider\":\"AWS\",\"sourceService\":\"s3.amazonaws.com\",\"operation\":\"Op" + id + "\","
                + "\"changeType\":\"" + change + "\"," + (result == null ? "" : "\"resultStatus\":\"" + result + "\",")
                + "\"eventTime\":\"2026-07-05T12:00:00Z\",\"targetResourceId\":\"s3://dlap-it-logs/" + id + "\","
                + "\"requestId\":\"hl-" + id + "\"" + extra + "}";
    }

    private void ingest(String body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/logingest/events").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("svc").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expectedStatus));
    }

    @BeforeEach
    void startAWindow() {
        telemetry.drain(BINDING);
        snapshots.refresh();
    }

    @Test
    void theStatusOfABindingCarriesItsCountsCoverageAndFormatsAndSurvivesTheWire() throws Exception {
        ingest(event("a1", "CREATE", "SUCCESS", ""), 202);
        ingest(event("a2", "ACCESS", "SUCCESS", ""), 202);
        ingest(event("a2", "ACCESS", "SUCCESS", ""), 202);   // a duplicate of the read
        ingest(event("a3", "ACCESS", null, ""), 422);        // quarantined
        snapshots.refresh();

        List<LogBindingStatus> sent = statusPublisher.publishAll();

        ArgumentCaptor<LogBindingStatus> published = ArgumentCaptor.forClass(LogBindingStatus.class);
        verify(statusTopic, org.mockito.Mockito.atLeastOnce()).publish(published.capture());
        assertThat(sent).extracting(LogBindingStatus::getBindingId)
                .as("a built-in binding reports once it carried a record, and only then").containsExactly(BINDING);
        LogBindingStatus s = published.getAllValues().get(published.getAllValues().size() - 1);
        assertThat(s.getBindingId()).isEqualTo(BINDING);
        assertThat(s.getState()).isEqualTo(BindingState.BINDING_STATE_ACTIVE);
        assertThat(s.getSourceKind()).isEqualTo("DLAP_NORMALIZED_AWS");
        assertThat(s.getConfigHash()).hasSize(64).isEqualTo(jdbc.queryForObject(
                "SELECT config_hash FROM dlap_logingest.source_binding WHERE binding_id = ?", String.class, BINDING));
        assertThat(s.getCounts().getParsedWithWarnings()).isEqualTo(3);
        assertThat(s.getCounts().getQuarantined()).isEqualTo(1);
        assertThat(s.getCounts().getPublished()).as("the create and the first read, not the duplicate").isEqualTo(2 + 1);
        assertThat(s.getCounts().getDuplicates()).isEqualTo(1);
        assertThat(s.getReadCoverage()).isEqualTo(ReadCoverage.READ_COVERAGE_CAPTURED);
        assertThat(s.getReadCoverageSince().getSeconds()).isEqualTo(java.time.Instant.parse("2026-07-05T12:00:00Z")
                .getEpochSecond());
        assertThat(s.getObservedFormatsList()).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getEnvelope()).isEqualTo("ingest-dto");
            assertThat(f.getSchemaVersion()).isEqualTo("v1");
        });
        assertThat(s.getObservedFormatsList().stream().mapToLong(f -> f.getCount()).sum())
                .as("one row per shape of the four records").isEqualTo(4);
        assertThat(s.getWindowEnd().getSeconds()).isGreaterThanOrEqualTo(s.getWindowStart().getSeconds());

        DlapLogEventCodec codec = DlapLogEventCodec.defaults();
        assertThat(codec.decodeMessage(codec.encodeMessage(s), LogBindingStatus.getDefaultInstance())).isEqualTo(s);

        // The next window starts empty.
        List<LogBindingStatus> next = statusPublisher.publishAll();
        assertThat(next.get(0).getCounts().getParsedWithWarnings()).isZero();
        assertThat(next.get(0).getCounts().getQuarantined()).isZero();
    }

    @Test
    void aBindingThatNeverCarriedARecordIsNotPublished() {
        List<LogBindingStatus> sent = statusPublisher.publishAll();

        assertThat(sent).extracting(LogBindingStatus::getBindingId).doesNotContain("dlap-rest-gcp");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theHealthApiListsTheBindingWithReasonsSamplesAndReadCoverage() throws Exception {
        ingest(event("b1", "ACCESS", "SUCCESS", ""), 202);
        ingest(event("b2", "ACCESS", null, ",\"principalId\":\"carol@example.com\""), 422);
        snapshots.refresh();

        mvc.perform(get("/api/v1/logingest/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipeline").value("registry"))
                .andExpect(jsonPath("$.selfTest.ok").value(true))
                .andExpect(jsonPath("$.bindings[?(@.bindingId=='dlap-rest-aws')].sourceKind")
                        .value("DLAP_NORMALIZED_AWS"));
        String one = mvc.perform(get("/api/v1/logingest/health/" + BINDING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.readCoverage").value("CAPTURED"))
                .andExpect(jsonPath("$.readSignalPresent").value(true))
                .andExpect(jsonPath("$.topQuarantineReasons[0].reason").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.topQuarantineReasons[0].count").value(1))
                .andExpect(jsonPath("$.topQuarantineReasons[0].samples[0].rawExcerpt").value(
                        org.hamcrest.Matchers.containsString("<email>")))
                .andExpect(jsonPath("$.versions[0].version").value("v1"))
                .andExpect(jsonPath("$.versions[0].matchesPin").value(true))
                .andExpect(jsonPath("$.fillRatios[?(@.field=='principal.id')]").exists())
                .andExpect(jsonPath("$.heuristicRatio").value(1.0))
                .andReturn().getResponse().getContentAsString();
        assertThat(one).doesNotContain("carol@example.com");
        mvc.perform(get("/api/v1/logingest/health/not-a-binding")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theDriftReportListsNewVersionsAndUnknownPathsWithoutValues() throws Exception {
        // The REST DTO drops members it does not know, so a record with an extra member enters like a
        // transport would deliver it: straight to the registry path.
        ingestService.ingest(LogHealthFixtures.record(BINDING, event("c1", "ACCESS", "SUCCESS",
                ",\"surpriseField\":\"top-secret-value\"")));
        snapshots.refresh();

        String json = mvc.perform(get("/api/v1/logingest/drift").param("since", "2020-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newVersions[?(@.bindingId=='dlap-rest-aws')].version").value("v1"))
                .andExpect(jsonPath("$.unknownPaths[?(@.path=='/surpriseField')].source").value("DLAP_NORMALIZED_AWS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).doesNotContain("top-secret-value");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theLogParsersComponentOfActuatorHealthReportsTheSelfTest() throws Exception {
        assertThat(selfTest.passed()).isTrue();

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.logParsers.status").value("UP"))
                .andExpect(jsonPath("$.components.logParsers.details.cases").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(4)));
    }

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void aNonAdminSeesNeitherTheHealthApiNorTheDriftReport() throws Exception {
        mvc.perform(get("/api/v1/logingest/health")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/logingest/drift")).andExpect(status().isForbidden());
        verify(statusTopic, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void thePrometheusEndpointOfTheRunningServiceCarriesTheSeriesTheAlertsRead() throws Exception {
        ingest(event("p1", "ACCESS", "SUCCESS", ""), 202);
        ingest(event("p2", "ACCESS", null, ""), 422);
        snapshots.refresh();

        String text = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        java.util.Set<String> exported = text.lines().filter(l -> !l.startsWith("#") && !l.isBlank())
                .map(l -> l.split("[{ ]", 2)[0]).collect(java.util.stream.Collectors.toSet());
        assertThat(exported).contains("dlap_logingest_records_total", "dlap_logingest_quarantine_total",
                "dlap_logingest_field_fill_ratio", "dlap_logingest_classification_heuristic_ratio",
                "dlap_logingest_read_signal_present", "dlap_logingest_version_observed",
                "dlap_logingest_version_first_seen_timestamp_seconds", "dlap_logingest_event_delay_seconds_count",
                "dlap_logingest_event_delay_seconds_bucket", "dlap_logingest_parser_selftest_ok");
        assertThat(text).contains("reason=\"MISSING_REQUIRED_FIELD\"", "binding=\"dlap-rest-aws\"",
                "source=\"DLAP_NORMALIZED_AWS\"", "outcome=\"QUARANTINED\"");
        assertThat(text.lines().filter(l -> l.startsWith("dlap_logingest_parser_selftest_ok")).findFirst())
                .hasValueSatisfying(l -> assertThat(l).endsWith(" 1.0"));
    }
}
