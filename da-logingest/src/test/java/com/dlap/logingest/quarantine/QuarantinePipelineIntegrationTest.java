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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.LogHealthIntegrationBase;
import com.dlap.logingest.metrics.PublishFailureListener;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import io.micrometer.core.instrument.Counter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LOG-03 acceptance, the sink side: with {@code dlap.logingest.pipeline=registry} a record the parsers
 * cannot take goes to the table (redacted, bounded) and to the topic, is counted by reason, and the
 * ingest answers with its quarantine id. PostgreSQL by Testcontainers (needs Docker); Kafka mocked.
 */
class QuarantinePipelineIntegrationTest extends LogHealthIntegrationBase {

    /** No resultStatus: quarantined as MISSING_REQUIRED_FIELD, with a pass-through event. */
    private static final String BROKEN = """
            {"sourceProvider":"AWS","operation":"GetObject","changeType":"ACCESS",
             "eventTime":"2026-07-05T12:00:00Z","targetResourceId":"s3://dlap-it-logs/c.csv",
             "requestId":"it-q-0001","principalId":"alice@example.com","callerIp":"203.0.113.10"}
            """;

    @Autowired
    private KafkaTemplate<?, ?> kafkaTemplate;

    @Autowired
    private QuarantineRecordRepository repository;

    private double count(String name, String tag, String value) {
        Counter c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anUnparsableRecordLandsInTheTableAndTheTopicAndIsCountedByReason() throws Exception {
        double before = count("dlap.logingest.quarantine", "reason", "MISSING_REQUIRED_FIELD");

        String body = mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(BROKEN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.outcome").value("QUARANTINED:MISSING_REQUIRED_FIELD"))
                .andReturn().getResponse().getContentAsString();

        // The table: one row, masked, bounded, with the hash of the original bytes.
        List<QuarantineRow> rows = repository.find(QuarantineRecordRepository.Filter.ALL, 10, 0);
        assertThat(rows).hasSize(1);
        QuarantineRow row = rows.get(0);
        assertThat(body).contains(row.quarantineId());
        assertThat(row.bindingId()).isEqualTo("dlap-rest-aws");
        assertThat(row.reason()).isEqualTo("MISSING_REQUIRED_FIELD");
        assertThat(row.reasonDetail()).isEqualTo("resultStatus is missing").doesNotContain("alice");
        assertThat(row.rawExcerpt()).doesNotContain("alice@example.com", "203.0.113.10")
                .contains("<email>", "<ip>");
        assertThat(row.rawSizeBytes()).isGreaterThan(0);
        assertThat(row.rawSha256()).hasSize(64);
        assertThat(row.passThroughEventId()).as("the audit fact was kept").isNotEmpty();
        assertThat(row.replayable()).as("a masked excerpt is never replayed").isFalse();
        assertThat(jdbc.queryForObject("SELECT parse_status FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'it-q-0001'", String.class)).isEqualTo("PARSE_STATUS_PARTIAL");

        // The topic: the same record, bounded and masked again.
        ArgumentCaptor<LogQuarantineRecord> sent = ArgumentCaptor.forClass(LogQuarantineRecord.class);
        verify(quarantineTopic).publish(sent.capture());
        assertThat(sent.getValue().getQuarantineId()).isEqualTo(row.quarantineId());
        assertThat(sent.getValue().getBindingId()).isEqualTo("dlap-rest-aws");
        assertThat(sent.getValue().getReason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD);
        assertThat(sent.getValue().getRawExcerpt()).doesNotContain("alice@example.com");
        assertThat(sent.getValue().getRawExcerpt().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);

        // The metrics: by reason, and the record counter by outcome with the documented labels.
        assertThat(count("dlap.logingest.quarantine", "reason", "MISSING_REQUIRED_FIELD") - before).isEqualTo(1.0);
        assertThat(meters.find("dlap.logingest.records").tag("outcome", "QUARANTINED")
                .tag("source", "DLAP_NORMALIZED_AWS").tag("format", "ingest-dto").tag("version", "v1")
                .tag("binding", "dlap-rest-aws").counter()).isNotNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theSameRecordTwiceIsOneRowAndTheEventIsNotDuplicated() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(BROKEN))
                    .andExpect(status().isUnprocessableEntity());
        }

        assertThat(repository.count(QuarantineRecordRepository.Filter.ALL)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'it-q-0001'", Long.class)).isEqualTo(1);
        verify(quarantineTopic, times(2)).publish(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aRecordThatParsesIsNotQuarantined() throws Exception {
        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON)
                        .content(BROKEN.replace("\"changeType\":\"ACCESS\",", "\"changeType\":\"ACCESS\","
                                + "\"resultStatus\":\"SUCCESS\",").replace("it-q-0001", "it-q-0002")))
                .andExpect(status().isAccepted());

        assertThat(repository.count(QuarantineRecordRepository.Filter.ALL)).isZero();
        verify(quarantineTopic, never()).publish(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theListEndpointReturnsTheStoredRowWithItsReplayability() throws Exception {
        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(BROKEN))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/v1/logingest/quarantine").param("reason", "MISSING_REQUIRED_FIELD")
                        .param("bindingId", "dlap-rest-aws").param("replayed", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.items[0].replayable").value(false))
                .andExpect(jsonPath("$.items[0].notReplayableReason").value("ALTERED"))
                .andExpect(jsonPath("$.items[0].rawRef.transport").value("REST"));
    }

    /** The JSON text of one NUL character inside a string value: backslash, u, four zeros. */
    private static final String JSON_NUL = "\\u0000";

    @Test
    @WithMockUser(roles = "ADMIN")
    void aNulInAQuarantinedRecordNoLongerFailsTheIngestOrBlocksThePartition() throws Exception {
        String body = BROKEN.replace("alice@example.com", "ali" + JSON_NUL + "ce@example.com")
                .replace("it-q-0001", "it-q-nul-1");

        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        List<QuarantineRow> rows = repository.find(QuarantineRecordRepository.Filter.ALL, 10, 0);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rawExcerpt()).contains("<email>").doesNotContain("\0");
        ArgumentCaptor<LogQuarantineRecord> sent = ArgumentCaptor.forClass(LogQuarantineRecord.class);
        verify(quarantineTopic).publish(sent.capture());
        assertThat(sent.getValue().getRawExcerpt()).doesNotContain("\0");
        assertThat(jdbc.queryForObject("SELECT parse_status FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'it-q-nul-1'", String.class)).as("the pass-through event with the NUL in its raw event is stored")
                .isEqualTo("PARSE_STATUS_PARTIAL");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aNulInTheRawEventOfAParsedRecordIsStoredAsItsEscapeText() throws Exception {
        String body = BROKEN.replace("\"changeType\":\"ACCESS\",", "\"changeType\":\"ACCESS\","
                + "\"resultStatus\":\"SUCCESS\",").replace("it-q-0001", "it-q-nul-2")
                .replace("alice@example.com", "ali" + JSON_NUL + "ce")
                .replace("\"requestId\"", "\"rawEvent\":{\"note\":\"x" + JSON_NUL + "y\",\"k" + JSON_NUL
                        + "\":[\"a" + JSON_NUL + "\"]},\"requestId\"");

        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("SELECT raw_event->>'note' FROM dlap_logingest.log_events WHERE "
                + "native_event_id = 'it-q-nul-2'", String.class)).isEqualTo("x" + JSON_NUL + "y");
        assertThat(jdbc.queryForObject("SELECT raw_event->('k" + JSON_NUL + "')->>0 FROM dlap_logingest.log_events "
                + "WHERE native_event_id = 'it-q-nul-2'", String.class)).isEqualTo("a" + JSON_NUL);
        assertThat(jdbc.queryForObject("SELECT principal_id FROM dlap_logingest.log_events WHERE "
                + "native_event_id = 'it-q-nul-2'", String.class)).isEqualTo("alice");
    }

    @Test
    void springBootWiresTheFailureCountingListenerIntoTheKafkaTemplate() {
        Object listener = ReflectionTestUtils.getField(kafkaTemplate, "producerListener");

        assertThat(listener).isInstanceOf(PublishFailureListener.class);
    }
}
