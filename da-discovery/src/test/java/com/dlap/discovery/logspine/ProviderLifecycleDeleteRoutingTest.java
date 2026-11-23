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

package com.dlap.discovery.logspine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * PARSE-02 (Q-612): the provider lifecycle deletes that da-logingest's parsers emit are routed onto
 * {@code dlap.discovery.asset.deleted}, keyed by their canonical URI. The events are the
 * {@code expected.json} events of the da-log-fixtures cases that da-logingest's contract tests pin
 * the parsers to (CloudTrail {@code s3lifecycle.amazonaws.com}, S3 server access log
 * {@code S3.EXPIRE.OBJECT}, BigQuery {@code InternalTableExpired}),
 * completed with the identity and origin every streamed event carries; da-logingest's
 * {@code ProviderLifecycleDeletePublishTest} shows them leaving the publisher in that form. PARSE-03
 * (D36): a version-qualified delete, a delete marker and an unlisted S3 lifecycle event are updates and
 * never reach the delete topic (Q-650, Q-651).
 */
class ProviderLifecycleDeleteRoutingTest {

    private static final Path CORPUS = Path.of("..", "da-log-fixtures", "src", "main", "resources", "dlap-fixtures");
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The provider lifecycle delete cases and how many deletes each holds. */
    private static final List<String> CASES = List.of(
            "cloudtrail/s3-file/1.11/s3-lifecycle-expiration-and-transition",
            "s3accesslog/cwl-json/V_1_0/lifecycle-expiration",
            "gcpaudit/json/bq-auditmetadata/table-expired",
            "gcpaudit/api-proto/bq-auditmetadata/table-expired");

    /**
     * The events of a case as the wire carries them: the expected fields plus the event id formula
     * and the streamed origin.
     *
     * @param caseDir the case directory under the corpus root
     * @return the events
     * @throws IOException when the case cannot be read
     */
    static List<DlapLogEvent> wireEvents(String caseDir) throws IOException {
        JsonNode expected = JSON.readTree(Files.readString(CORPUS.resolve(caseDir).resolve("expected.json")));
        List<DlapLogEvent> out = new ArrayList<>();
        for (JsonNode e : expected.path("events")) {
            DlapLogEvent partial = CODEC.decode(JSON.writeValueAsString(e));
            out.add(partial.toBuilder()
                    .setEventId(LogEventIds.eventId(partial.getSourceProvider(), partial.getNativeEventId()))
                    .setOrigin(Origin.ORIGIN_CLOUD_LOG)
                    .setSchemaVersion("v1")
                    .build());
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyProviderLifecycleDeleteIsRoutedOntoTheDeleteTopicByItsCanonicalUri() throws IOException {
        int deletes = 0;
        for (String c : CASES) {
            for (DlapLogEvent e : wireEvents(c)) {
                if (e.getChangeType() != ChangeType.DELETE) {
                    continue;
                }
                deletes++;
                KafkaTemplate<String, Object> kafka = RouterKafkaMocks.acceptingTemplate();
                Acknowledgment ack = mock(Acknowledgment.class);
                DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(kafka, JSON, new SimpleMeterRegistry());

                router.route(CODEC.toMap(e), DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0L, ack);

                String uri = e.getTargetResource().getCanonicalUri();
                assertThat(uri).as("canonical URI of %s in %s", e.getOperation(), c).isNotBlank();
                verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED), eq(uri), any(JsonNode.class));
                verify(kafka, never()).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED), anyString(), any());
                verify(ack).acknowledge();
            }
        }
        // PARSE-03 (D36, Q-651): the access log's delete marker is an UPDATE now, so it holds one delete.
        assertThat(deletes).as("CloudTrail 1, access log 1, BigQuery 2").isEqualTo(4);
    }

    /** PARSE-03 cases whose events are version-qualified or unlisted lifecycle events: none is a delete. */
    private static final List<String> NO_DELETE_CASES = List.of(
            "s3accesslog/cwl-json/V_1_0/versioned-deletes",
            "cloudtrail/s3-file/1.11/s3-lifecycle-unlisted-events",
            "cloudtrail/s3-file/1.11/s3-lifecycle-unlisted-heuristic");

    @Test
    @SuppressWarnings("unchecked")
    void versionQualifiedAndUnlistedLifecycleEventsNeverReachTheDeleteTopic() throws IOException {
        List<DlapLogEvent> events = new ArrayList<>();
        for (String c : NO_DELETE_CASES) {
            events.addAll(wireEvents(c));
        }
        events.addAll(wireEvents("cloudtrail/s3-file/1.11/s3-versioned-deletes").stream()
                .filter(e -> e.getChangeType() == ChangeType.UPDATE).toList());
        events.addAll(wireEvents("s3accesslog/cwl-json/V_1_0/lifecycle-expiration").stream()
                .filter(e -> "S3.CREATE.DELETEMARKER".equals(e.getOperation())).toList());
        assertThat(events).as("3 access log, 2 unlisted, 1 heuristic, 3 CloudTrail version deletes, 1 marker")
                .hasSize(10);
        int modified = 0;
        for (DlapLogEvent e : events) {
            assertThat(e.getChangeType()).as(e.getOperation()).isEqualTo(ChangeType.UPDATE);
            KafkaTemplate<String, Object> kafka = RouterKafkaMocks.acceptingTemplate();
            DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(kafka, JSON, new SimpleMeterRegistry());

            router.route(CODEC.toMap(e), DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0L, mock(Acknowledgment.class));

            verify(kafka, never()).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED), anyString(), any());
            String uri = e.getTargetResource().getCanonicalUri();
            if (!uri.isEmpty()) {
                verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED), eq(uri), any(JsonNode.class));
                modified++;
            }
        }
        assertThat(modified).as("every event that names an object goes to the modified topic").isEqualTo(9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCurrentObjectDeleteOfTheVersionCaseStillGoesToTheDeleteTopic() throws IOException {
        DlapLogEvent current = wireEvents("cloudtrail/s3-file/1.11/s3-versioned-deletes").stream()
                .filter(e -> e.getChangeType() == ChangeType.DELETE).findFirst().orElseThrow();
        KafkaTemplate<String, Object> kafka = RouterKafkaMocks.acceptingTemplate();
        DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(kafka, JSON, new SimpleMeterRegistry());

        router.route(CODEC.toMap(current), DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0L, mock(Acknowledgment.class));

        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED),
                eq("s3://dlap-aws-e2e/curated/2026/old.parquet"), any(JsonNode.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLifecycleTransitionGoesToTheArchiveTopicNotTheDeleteTopic() throws IOException {
        DlapLogEvent transition = wireEvents(CASES.get(0)).stream()
                .filter(e -> e.getChangeType() == ChangeType.ARCHIVE).findFirst().orElseThrow();
        KafkaTemplate<String, Object> kafka = RouterKafkaMocks.acceptingTemplate();
        DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(kafka, JSON, new SimpleMeterRegistry());

        router.route(CODEC.toMap(transition), DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0L, mock(Acknowledgment.class));

        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_ARCHIVED),
                eq(transition.getTargetResource().getCanonicalUri()), any(JsonNode.class));
        verify(kafka, never()).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED), anyString(), any());
    }
}
