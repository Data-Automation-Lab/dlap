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

package com.dlap.logingest.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.LogHealthIntegrationBase;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayRequest;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayResult;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.lock.PgAdvisoryLocks;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * LOG-03 acceptance, replay: ADMIN only and idempotent. Rows that an older parser quarantined are put
 * in the table as such (their excerpt is the exact record), the current registry then takes them, and
 * the assertions follow the rows, the events and the publisher. PostgreSQL by Testcontainers (needs
 * Docker); Kafka mocked.
 */
class QuarantineReplayIntegrationTest extends LogHealthIntegrationBase {

    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");
    private static final String BINDING = "dlap-rest-aws";

    @Autowired
    private QuarantineRecordRepository repository;

    @Autowired
    private QuarantineReplayService replay;

    @Autowired
    private PgAdvisoryLocks locks;

    @BeforeEach
    void bindings() {
        QuarantineFixtures.insertBinding(jdbc, "gcp-gone");
    }

    private LogQuarantineRecord olderParserQuarantined(String requestId, long index, String text) {
        return QuarantineFixtures.exact(BINDING, text, QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION, index,
                T0);
    }

    private static String valid(String requestId) {
        return "{\"sourceProvider\":\"AWS\",\"sourceService\":\"s3.amazonaws.com\",\"operation\":\"GetObject\","
                + "\"changeType\":\"ACCESS\",\"resultStatus\":\"SUCCESS\",\"eventTime\":\"2026-07-05T12:00:00Z\","
                + "\"targetResourceId\":\"s3://dlap-it-logs/" + requestId + "\",\"requestId\":\"" + requestId + "\"}";
    }

    private static String stillBroken(String requestId) {
        return valid(requestId).replace("\"resultStatus\":\"SUCCESS\",", "");
    }

    @Test
    void aRecordThatNowParsesIsResolvedPublishedOnceAndNeverSelectedAgain() {
        LogQuarantineRecord q = olderParserQuarantined("rp-1", 1, valid("rp-1"));
        repository.upsert(q, T0);

        ReplayResult first = replay.replay(new ReplayRequest(null, null, null, null, null, false), "test-admin");

        assertThat(first.selected()).isEqualTo(1);
        assertThat(first.replayable()).isEqualTo(1);
        assertThat(first.resolved()).isEqualTo(1);
        assertThat(first.stillQuarantined()).isZero();
        assertThat(first.resolvedIds()).containsExactly(q.getQuarantineId());
        QuarantineRow row = repository.findById(q.getQuarantineId()).orElseThrow();
        assertThat(row.replayedAt()).isNotNull();
        assertThat(row.replayCount()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT origin, binding_id, parse_status FROM dlap_logingest.log_events "
                + "WHERE native_event_id = 'rp-1'")).containsEntry("origin", "ORIGIN_REPLAY")
                .containsEntry("binding_id", BINDING).containsEntry("parse_status", "PARSE_STATUS_WARNINGS");
        verify(publisher, times(1)).publish(anyString(), anyMap());

        ReplayResult second = replay.replay(new ReplayRequest(null, null, null, null, null, false), "test-admin");

        assertThat(second.selected()).as("idempotent: a resolved record is not selected again").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'rp-1'", Long.class)).isEqualTo(1);
        verify(publisher, times(1)).publish(anyString(), anyMap());
    }

    @Test
    void aRecordThatIsStillBrokenStaysPendingWithOneRowAndACount() {
        LogQuarantineRecord q = olderParserQuarantined("rp-2", 2, stillBroken("rp-2"));
        repository.upsert(q, T0);

        ReplayResult r = replay.replay(new ReplayRequest(null, null, null, null, null, false), "test-admin");

        assertThat(r.stillQuarantined()).isEqualTo(1);
        assertThat(r.resolved()).isZero();
        assertThat(repository.count(QuarantineRecordRepository.Filter.ALL)).as("the sink updated the same row").isEqualTo(1);
        QuarantineRow row = repository.findById(q.getQuarantineId()).orElseThrow();
        assertThat(row.replayedAt()).isNull();
        assertThat(row.replayCount()).isEqualTo(1);
        assertThat(row.reason()).as("the current parser's verdict replaced the old one")
                .isEqualTo("MISSING_REQUIRED_FIELD");
        assertThat(row.quarantinedAt()).isEqualTo(T0);

        ReplayResult again = replay.replay(new ReplayRequest(null, null, null, null, null, false), "test-admin");
        assertThat(again.selected()).as("still pending, so a later fix can replay it").isEqualTo(1);
        assertThat(repository.findById(q.getQuarantineId()).orElseThrow().replayCount()).isEqualTo(2);
    }

    @Test
    void truncatedMaskedUnrunnableAndFilteredRowsAreCountedNotReplayed() {
        LogQuarantineRecord truncated = olderParserQuarantined("rp-3", 3, valid("rp-3")).toBuilder()
                .setRawTruncated(true).build();
        LogQuarantineRecord masked = olderParserQuarantined("rp-4", 4, valid("rp-4").replace("rp-4", "bob@example.com"))
                .toBuilder().setRawExcerpt(valid("rp-4").replace("rp-4", "<email>")).build();
        LogQuarantineRecord unrunnable = QuarantineFixtures.exact("gcp-gone", valid("rp-5"),
                QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION, 5, T0);
        LogQuarantineRecord good = olderParserQuarantined("rp-6", 6, valid("rp-6"));
        for (LogQuarantineRecord r : List.of(truncated, masked, unrunnable, good)) {
            repository.upsert(r, T0);
        }

        ReplayResult r = replay.replay(new ReplayRequest(null, null, null, null, null, false), "test-admin");

        assertThat(r.selected()).isEqualTo(4);
        assertThat(r.notReplayableTruncated()).isEqualTo(1);
        assertThat(r.notReplayableAltered()).isEqualTo(1);
        assertThat(r.bindingNotRunning()).isEqualTo(1);
        assertThat(r.replayable()).isEqualTo(1);
        assertThat(r.resolved()).isEqualTo(1);
        assertThat(repository.findById(truncated.getQuarantineId()).orElseThrow().replayCount()).isZero();
        assertThat(repository.findById(masked.getQuarantineId()).orElseThrow().replayCount()).isZero();
        assertThat(repository.findById(unrunnable.getQuarantineId()).orElseThrow().replayCount()).isZero();
    }

    @Test
    void theFiltersNarrowWhatIsReplayedAndTheLimitCapsIt() {
        LogQuarantineRecord a = olderParserQuarantined("rp-7", 7, valid("rp-7"));
        LogQuarantineRecord b = olderParserQuarantined("rp-8", 8, valid("rp-8")).toBuilder()
                .setReason(QuarantineReason.QUARANTINE_REASON_INVALID_JSON).setDetectedVersion("v2").build();
        LogQuarantineRecord c = olderParserQuarantined("rp-9", 9, valid("rp-9"));
        for (LogQuarantineRecord r : List.of(a, b, c)) {
            repository.upsert(r, T0);
        }

        assertThat(replay.replay(new ReplayRequest(null, "INVALID_JSON", null, null, null, true), "t").selected())
                .isEqualTo(1);
        assertThat(replay.replay(new ReplayRequest(null, null, "v2", null, null, true), "t").selected()).isEqualTo(1);
        assertThat(replay.replay(new ReplayRequest("gcp-gone", null, null, null, null, true), "t").selected()).isZero();
        assertThat(replay.replay(new ReplayRequest(null, null, null, List.of(a.getQuarantineId()), null, true), "t")
                .selected()).isEqualTo(1);
        assertThat(replay.replay(new ReplayRequest(null, null, null, null, 2, true), "t").selected()).isEqualTo(2);
        assertThatThrownBy(() -> replay.replay(new ReplayRequest(null, "NOT_A_REASON", null, null, null, true), "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replay.replay(new ReplayRequest(null, null, null, null, 0, true), "t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDryRunCountsAndChangesNothing() {
        LogQuarantineRecord q = olderParserQuarantined("rp-10", 10, valid("rp-10"));
        repository.upsert(q, T0);

        ReplayResult r = replay.replay(new ReplayRequest(null, null, null, null, null, true), "t");

        assertThat(r.dryRun()).isTrue();
        assertThat(r.replayable()).isEqualTo(1);
        assertThat(r.resolved()).isZero();
        assertThat(repository.findById(q.getQuarantineId()).orElseThrow().replayedAt()).isNull();
        assertThat(repository.findById(q.getQuarantineId()).orElseThrow().replayCount()).isZero();
        verify(publisher, never()).publish(anyString(), anyMap());
    }

    @Test
    void onlyOneReplayRunsAtATime() {
        Optional<AdvisoryLocks.Lease> held = locks.tryLock(QuarantineReplayService.LOCK_NAMESPACE,
                QuarantineReplayService.LOCK_KEY);
        assertThat(held).isPresent();
        try {
            assertThatThrownBy(() -> replay.replay(new ReplayRequest(null, null, null, null, null, true), "t"))
                    .isInstanceOf(QuarantineReplayService.ReplayBusyException.class);
        } finally {
            held.get().close();
        }

        assertThat(replay.replay(new ReplayRequest(null, null, null, null, null, true), "t").dryRun()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theEndpointReplaysForAnAdminAndAnswersWithTheCounts() throws Exception {
        LogQuarantineRecord q = olderParserQuarantined("rp-11", 11, valid("rp-11"));
        repository.upsert(q, T0);

        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"" + BINDING + "\",\"reason\":\"UNKNOWN_MAJOR_VERSION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(1))
                .andExpect(jsonPath("$.resolved").value(1))
                .andExpect(jsonPath("$.resolvedIds[0]").value(q.getQuarantineId()));
    }

    @Test
    @WithMockUser(roles = {"DATA_ENGINEER", "CDO"})
    void aNonAdminCannotReplay() throws Exception {
        repository.upsert(olderParserQuarantined("rp-12", 12, valid("rp-12")), T0);

        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf())).andExpect(status().isForbidden());

        assertThat(repository.count(new QuarantineRecordRepository.Filter(null, null, null, false, null, null, null)))
                .isEqualTo(1);
    }

    @Test
    void theReplayedEventKeepsTheEventIdOfTheOriginalFormula() {
        LogQuarantineRecord q = olderParserQuarantined("rp-13", 13, valid("rp-13"));
        repository.upsert(q, T0);
        replay.replay(new ReplayRequest(null, null, null, null, null, false), "t");

        String expected = com.dlap.common.logevent.LogEventIds.eventId(com.dlap.proto.log.v1.SourceProvider.AWS, "rp-13");
        assertThat(jdbc.queryForObject("SELECT event_id FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'rp-13'", String.class)).isEqualTo(expected);
    }
}
