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

import com.dlap.logingest.quarantine.QuarantineRecordRepository.Filter;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The quarantine table on PostgreSQL with the real Flyway migrations (Testcontainers, needs Docker):
 * idempotent upsert, filters and paging, the replay marks, retention deletes and the reason summary.
 */
class QuarantineRecordRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
    private static final String JSON_REASON = "INVALID_JSON";

    private static JdbcTemplate jdbc;
    private static QuarantineRecordRepository repository;

    @BeforeAll
    static void database() {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        jdbc = new JdbcTemplate(ds);
        repository = new QuarantineRecordRepository(jdbc, new ObjectMapper(), LogIngestPostgres.SCHEMA);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_logingest.quarantine_record");
        QuarantineFixtures.insertBinding(jdbc, "b1");
        QuarantineFixtures.insertBinding(jdbc, "b2");
    }

    private static LogQuarantineRecord record(String binding, String text, QuarantineReason reason, long index) {
        return QuarantineFixtures.exact(binding, text, reason, index, T0);
    }

    @Test
    void aRecordIsStoredWithEveryFieldAndReadBack() {
        LogQuarantineRecord r = record("b1", "{\"a\":1}", QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH, 7)
                .toBuilder().setEventTime(Timestamp.newBuilder().setSeconds(T0.minusSeconds(60).getEpochSecond()))
                .setNativeEventId("native-1").setPassThroughEventId("pt-1").setPinnedVersion("v1").build();

        repository.upsert(r, T0);

        QuarantineRow row = repository.findById(r.getQuarantineId()).orElseThrow();
        assertThat(row.bindingId()).isEqualTo("b1");
        assertThat(row.sourceProvider()).isEqualTo("AWS");
        assertThat(row.sourceKind()).isEqualTo("DLAP_NORMALIZED_AWS");
        assertThat(row.envelope()).isEqualTo("ingest-dto");
        assertThat(row.detectedVersion()).isEqualTo("v1");
        assertThat(row.pinnedVersion()).isEqualTo("v1");
        assertThat(row.parserVersion()).isEqualTo("dlap-normalized@0.9.0");
        assertThat(row.reason()).isEqualTo("FIELD_TYPE_MISMATCH");
        assertThat(row.reasonDetail()).isEqualTo("changeType is not a known value");
        assertThat(row.rawRef()).containsEntry("transport", "REST").containsEntry("uri", "rest:/api/v1/logingest/events")
                .containsEntry("cursor", "");
        assertThat(((Number) row.rawRef().get("recordIndex")).longValue()).isEqualTo(7);
        assertThat(row.rawSha256()).isEqualTo(r.getRawSha256());
        assertThat(row.rawSizeBytes()).isEqualTo(7);
        assertThat(row.rawExcerpt()).isEqualTo("{\"a\":1}");
        assertThat(row.rawTruncated()).isFalse();
        assertThat(row.eventTime()).isEqualTo(T0.minusSeconds(60));
        assertThat(row.nativeEventId()).isEqualTo("native-1");
        assertThat(row.passThroughEventId()).isEqualTo("pt-1");
        assertThat(row.quarantinedAt()).isEqualTo(T0);
        assertThat(row.replayedAt()).isNull();
        assertThat(row.replayCount()).isZero();
        assertThat(row.replayable()).isTrue();
        assertThat(repository.findById("no-such-id")).isEmpty();
    }

    @Test
    void quarantiningTheSameRecordTwiceIsOneRowThatKeepsItsFirstTimeAndTakesTheLatestVerdict() {
        LogQuarantineRecord first = record("b1", "{}", QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION, 0);
        repository.upsert(first, T0);
        repository.markReplayAttempt(first.getQuarantineId(), false, T0.plusSeconds(1));

        LogQuarantineRecord later = first.toBuilder().setParserVersion("dlap-normalized@1.0.0")
                .setReason(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD).setReasonDetail("operation is missing")
                .build();
        repository.upsert(later, T0.plusSeconds(3600));

        assertThat(repository.count(Filter.ALL)).isEqualTo(1);
        QuarantineRow row = repository.findById(first.getQuarantineId()).orElseThrow();
        assertThat(row.reason()).isEqualTo("MISSING_REQUIRED_FIELD");
        assertThat(row.parserVersion()).isEqualTo("dlap-normalized@1.0.0");
        assertThat(row.quarantinedAt()).as("the retention clock starts at the first quarantine").isEqualTo(T0);
        assertThat(row.replayCount()).as("an upsert leaves the replay marks alone").isEqualTo(1);
    }

    @Test
    void aRecordOfAnUnknownBindingIsRefusedSoNothingIsLostSilently() {
        LogQuarantineRecord r = record("no-such-binding", "{}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 0);

        assertThatThrownBy(() -> repository.upsert(r, T0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void filtersNarrowTheListAndTheNewestComeFirst() {
        for (int i = 0; i < 5; i++) {
            repository.upsert(record("b1", "{\"i\":" + i + "}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, i),
                    T0.plusSeconds(i));
        }
        LogQuarantineRecord other = record("b2", "[]", QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION, 0)
                .toBuilder().setDetectedVersion("9.9").build();
        repository.upsert(other, T0.plusSeconds(10));

        assertThat(repository.count(Filter.ALL)).isEqualTo(6);
        assertThat(repository.find(Filter.ALL, 100, 0)).first().extracting(QuarantineRow::quarantineId)
                .isEqualTo(other.getQuarantineId());
        assertThat(repository.count(new Filter("b1", null, null, null, null, null, null))).isEqualTo(5);
        assertThat(repository.count(new Filter(null, JSON_REASON, null, null, null, null, null))).isEqualTo(5);
        assertThat(repository.count(new Filter(null, null, "9.9", null, null, null, null))).isEqualTo(1);
        assertThat(repository.count(new Filter("b2", JSON_REASON, null, null, null, null, null))).isZero();
        assertThat(repository.count(new Filter(null, null, null, null, T0.plusSeconds(2), T0.plusSeconds(4), null)))
                .as("from is inclusive, to is exclusive").isEqualTo(2);
        List<QuarantineRow> page2 = repository.find(new Filter("b1", null, null, null, null, null, null), 2, 2);
        assertThat(page2).hasSize(2).extracting(QuarantineRow::quarantinedAt)
                .containsExactly(T0.plusSeconds(2), T0.plusSeconds(1));
        assertThat(repository.count(new Filter(null, null, null, null, null, null,
                List.of(other.getQuarantineId(), "unknown")))).isEqualTo(1);
    }

    @Test
    void pendingRowsExcludeResolvedOnesAndAreOldestFirst() {
        LogQuarantineRecord a = record("b1", "{\"a\":1}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 0);
        LogQuarantineRecord b = record("b1", "{\"b\":1}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 1);
        LogQuarantineRecord c = record("b1", "{\"c\":1}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 2);
        repository.upsert(b, T0.plusSeconds(2));
        repository.upsert(a, T0.plusSeconds(1));
        repository.upsert(c, T0.plusSeconds(3));

        assertThat(repository.markReplayAttempt(a.getQuarantineId(), true, T0.plusSeconds(100))).isTrue();
        assertThat(repository.markReplayAttempt(b.getQuarantineId(), false, T0.plusSeconds(100))).isTrue();
        assertThat(repository.markReplayAttempt("missing", true, T0)).isFalse();

        assertThat(repository.pending(Filter.ALL, 10)).extracting(QuarantineRow::quarantineId)
                .containsExactly(b.getQuarantineId(), c.getQuarantineId());
        assertThat(repository.pending(Filter.ALL, 1)).hasSize(1);
        QuarantineRow resolved = repository.findById(a.getQuarantineId()).orElseThrow();
        assertThat(resolved.replayedAt()).isEqualTo(T0.plusSeconds(100));
        assertThat(resolved.replayCount()).isEqualTo(1);
        assertThat(repository.findById(b.getQuarantineId()).orElseThrow().replayedAt()).isNull();
        assertThat(repository.count(new Filter(null, null, null, true, null, null, null))).isEqualTo(1);
        assertThat(repository.count(new Filter(null, null, null, false, null, null, null))).isEqualTo(2);

        repository.markReplayAttempt(a.getQuarantineId(), true, T0.plusSeconds(500));
        assertThat(repository.findById(a.getQuarantineId()).orElseThrow().replayedAt())
                .as("the first resolution time stays").isEqualTo(T0.plusSeconds(100));
    }

    @Test
    void retentionDeletesInBatchesBeforeTheCutoffOnly() {
        for (int i = 0; i < 5; i++) {
            repository.upsert(record("b1", "{\"old\":" + i + "}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, i),
                    T0.minusSeconds(100 + i));
        }
        repository.upsert(record("b1", "{\"new\":1}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 99), T0);

        assertThat(repository.deleteBefore(T0, 3)).isEqualTo(3);
        assertThat(repository.deleteBefore(T0, 3)).isEqualTo(2);
        assertThat(repository.deleteBefore(T0, 3)).isZero();
        assertThat(repository.count(Filter.ALL)).isEqualTo(1);
    }

    @Test
    void reasonsSamplesAndCountsSummarizeABindingInAWindow() {
        for (int i = 0; i < 3; i++) {
            repository.upsert(record("b1", "{\"j\":" + i + "}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, i),
                    T0.plusSeconds(i));
        }
        repository.upsert(record("b1", "[1]", QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION, 10), T0);
        repository.upsert(record("b1", "[2]", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 11), T0.minusSeconds(7200));
        repository.upsert(record("b2", "[3]", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 12), T0);

        Instant since = T0.minusSeconds(60);
        assertThat(repository.topReasons("b1", since, 5)).containsExactly(
                new QuarantineRecordRepository.ReasonCount("INVALID_JSON", 3),
                new QuarantineRecordRepository.ReasonCount("UNKNOWN_MAJOR_VERSION", 1));
        assertThat(repository.topReasons("b1", since, 1)).hasSize(1);
        assertThat(repository.samples("b1", "INVALID_JSON", since, 2)).hasSize(2)
                .first().extracting(QuarantineRow::quarantinedAt).isEqualTo(T0.plusSeconds(2));
        Map<String, Long> counts = repository.countsByBinding(since);
        assertThat(counts).containsEntry("b1", 4L).containsEntry("b2", 1L);
    }

    @Test
    void theSchemaNameMustBeAPlainIdentifier() {
        assertThatThrownBy(() -> new QuarantineRecordRepository(jdbc, new ObjectMapper(), "x; DROP TABLE y"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
