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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * The two sinks of the quarantine: the table (durable, redacted, bounded, idempotent) and the topic
 * (proto3 JSON, a JSON object and not a string holding JSON, keyed by binding).
 */
class QuarantineSinksTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
    private static final String SECRET_RECORD = "{\"principal\":\"alice@example.com\",\"from\":\"203.0.113.10\"}";

    private static JdbcTemplate jdbc;
    private static QuarantineRecordRepository repository;

    private final MutableClock clock = new MutableClock(T0);

    @BeforeAll
    static void database() {
        jdbc = new JdbcTemplate(LogIngestPostgres.migratedDatabase());
        repository = new QuarantineRecordRepository(jdbc, new ObjectMapper(), LogIngestPostgres.SCHEMA);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_logingest.quarantine_record");
        QuarantineFixtures.insertBinding(jdbc, "b1");
    }

    private TableQuarantineSink tableSink(QuarantineProperties props) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("clock", clock));
        return new TableQuarantineSink(repository, props, beans.getBeanProvider(java.time.Clock.class));
    }

    @Test
    void theTableSinkStoresARedactedBoundedRowOnceHoweverOftenItIsAsked() {
        LogQuarantineRecord r = QuarantineFixtures.exact("b1", SECRET_RECORD,
                QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH, 3, T0);
        TableQuarantineSink sink = tableSink(QuarantineProperties.defaults());

        sink.accept(r);
        sink.accept(r);

        assertThat(repository.count(QuarantineRecordRepository.Filter.ALL)).isEqualTo(1);
        QuarantineRow row = repository.findById(r.getQuarantineId()).orElseThrow();
        assertThat(row.rawExcerpt()).doesNotContain("alice@example.com", "203.0.113.10").contains("<email>", "<ip>");
        assertThat(row.rawSha256()).as("the hash is of the original bytes").isEqualTo(r.getRawSha256());
        assertThat(row.rawSizeBytes()).isEqualTo(SECRET_RECORD.getBytes(StandardCharsets.UTF_8).length);
        assertThat(row.quarantinedAt()).isEqualTo(T0);
        assertThat(row.replayable()).as("a masked copy must never be replayed").isFalse();
        assertThat(row.notReplayableReason()).isEqualTo(QuarantineRow.NotReplayable.ALTERED);
    }

    @Test
    void aNulInTheExcerptNoLongerFailsTheTableSink() {
        char nul = 0;
        String text = "{\"a\":\"x" + nul + "y\"}";
        LogQuarantineRecord r = QuarantineFixtures.exact("b1", text,
                QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 0, T0);
        TableQuarantineSink sink = tableSink(QuarantineProperties.defaults());

        sink.accept(r);

        QuarantineRow row = repository.findById(r.getQuarantineId()).orElseThrow();
        assertThat(row.rawExcerpt()).isEqualTo("{\"a\":\"xy\"}");
        assertThat(row.rawSha256()).as("the hash is still of the original bytes").isEqualTo(r.getRawSha256());
        assertThat(row.replayable()).as("the stored excerpt differs from the original").isFalse();
    }

    @Test
    void theTableSinkBoundsAnOversizeExcerptAndDetailEvenFromAnotherProducer() {
        String big = "{\"k\":\"" + "x".repeat(10_000) + "\"}";
        LogQuarantineRecord r = QuarantineFixtures.exact("b1", big, QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD,
                0, T0).toBuilder().setReasonDetail("d".repeat(4000)).build();

        tableSink(QuarantineProperties.defaults()).accept(r);

        QuarantineRow row = repository.findById(r.getQuarantineId()).orElseThrow();
        assertThat(row.rawExcerpt().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        assertThat(row.rawTruncated()).isTrue();
        assertThat(row.reasonDetail().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
        assertThat(row.rawSizeBytes()).as("the size is the full record's").isEqualTo(big.length());
        assertThat(row.notReplayableReason()).isEqualTo(QuarantineRow.NotReplayable.TRUNCATED);
    }

    @Test
    void theExcerptCeilingHoldsWhateverTheSettingSays() {
        QuarantineProperties props = new QuarantineProperties(true, true, 1_000_000, 30, true, 5000, 500);

        assertThat(props.maxExcerptBytes()).isEqualTo(QuarantineProperties.EXCERPT_CEILING);
    }

    @Test
    void aDatabaseFailureFailsTheSinkSoTheRecordIsNotDropped() {
        LogQuarantineRecord r = QuarantineFixtures.exact("not-a-binding", "{}",
                QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 0, T0);
        TableQuarantineSink sink = tableSink(QuarantineProperties.defaults());

        assertThatThrownBy(() -> sink.accept(r)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> template() {
        return mock(KafkaTemplate.class);
    }

    @Test
    void theTopicGetsTheProto3JsonObjectKeyedByBinding() throws Exception {
        KafkaTemplate<String, Object> kafka = template();
        QuarantineTopicPublisher publisher = new QuarantineTopicPublisher(kafka, new ObjectMapper());
        TopicQuarantineSink sink = new TopicQuarantineSink(publisher, QuarantineProperties.defaults());
        LogQuarantineRecord r = QuarantineFixtures.exact("b1", SECRET_RECORD,
                QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH, 3, T0);

        sink.accept(r);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE), eq("b1"), value.capture());
        assertThat(DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE).isEqualTo("dlap.log.event.quarantine");
        assertThat(value.getValue()).isInstanceOf(Map.class);

        // The shared template serializes values with JsonSerializer: the wire bytes are one JSON object.
        byte[] bytes;
        try (JsonSerializer<Object> serializer = new JsonSerializer<>(new ObjectMapper())) {
            bytes = serializer.serialize(DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE, value.getValue());
        }
        String wire = new String(bytes, StandardCharsets.UTF_8);
        assertThat(wire).startsWith("{").doesNotContain("alice@example.com");
        LogQuarantineRecord back = DlapLogEventCodec.defaults().decodeMessage(wire, LogQuarantineRecord.getDefaultInstance());
        assertThat(back.getQuarantineId()).isEqualTo(r.getQuarantineId());
        assertThat(back.getBindingId()).isEqualTo("b1");
        assertThat(back.getReason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH);
        assertThat(back.getRawExcerpt()).contains("<email>");
        assertThat(back.getRawSha256()).isEqualTo(r.getRawSha256());
        assertThat(back.getRawRef().getRecordIndex()).isEqualTo(3);
        assertThat(back.getSchemaVersion()).isEqualTo("v1");
    }

    @Test
    void aSendThatFailsSynchronouslyFailsTheSink() {
        KafkaTemplate<String, Object> kafka = template();
        when(kafka.send(any(String.class), any(String.class), any(Object.class)))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("metadata"));
        TopicQuarantineSink sink = new TopicQuarantineSink(new QuarantineTopicPublisher(kafka, new ObjectMapper()),
                QuarantineProperties.defaults());

        assertThatThrownBy(() -> sink.accept(QuarantineFixtures.exact("b1", "{}",
                QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 0, T0)))
                .isInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
    }

    @Test
    void theSinksRunTableFirstThenTopic() {
        List<QuarantineSink> sinks = new java.util.ArrayList<>(List.of(
                new TopicQuarantineSink(new QuarantineTopicPublisher(template(), new ObjectMapper()),
                        QuarantineProperties.defaults()),
                tableSink(QuarantineProperties.defaults())));
        org.springframework.core.annotation.AnnotationAwareOrderComparator.sort(sinks);

        assertThat(sinks.get(0)).isInstanceOf(TableQuarantineSink.class);
        assertThat(sinks.get(1)).isInstanceOf(TopicQuarantineSink.class);
    }
}
