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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.errors.NetworkException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The shared send path of da-discovery's spine producers (SPINE-01): the event is printed once in
 * the canonical form, and the send is awaited, so a lost event is an exception and not a log line.
 */
class SpineEventsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final Duration SHORT = Duration.ofMillis(200);

    private static JsonNode legacyBridgeShape() throws Exception {
        return JSON.readTree("""
                {"eventId":"e-1","nativeEventId":"DLAP_CRAWLER|c-1","changeType":"CREATE","source":"crawler",
                 "operation":"DISCOVERY","sourceProvider":"GCP","eventTime":"2026-10-19T09:00:00Z",
                 "targetResource":{"id":"gs://demo-bucket","kind":"GCS_BUCKET","canonicalUri":"gs://demo-bucket",
                                   "sizeBytes":"0","createdTime":"2026-01-15T12:00:00Z",
                                   "technicalMetadata":{"storageClass":"STANDARD"}}}
                """);
    }

    @Test
    void theFlatTreeIsPrintedOnceAsTheStrictlyDecodableCanonicalObject() throws Exception {
        String json = SpineEvents.encode(legacyBridgeShape());

        assertThat(json).startsWith("{").doesNotContain("\"source\":");
        DlapLogEvent event = CODEC.decodeStrict(json);
        assertThat(event.getEventId()).isEqualTo("e-1");
        assertThat(event.getChangeType()).isEqualTo(ChangeType.CREATE);
        assertThat(event.getOrigin()).as("the source marker became origin").isEqualTo(Origin.ORIGIN_CRAWLER);
        assertThat(event.getSourceFormat()).isEqualTo("DLAP_CRAWLER/resource-event");
        assertThat(event.getTargetResource().getSizeBytes()).isZero();
        assertThat(event.getTargetResource().getTechnicalMetadataMap()).containsEntry("storageClass", "STANDARD");
    }

    @Test
    void aValueTheContractCannotHoldIsRefusedWithoutQuotingIt() throws Exception {
        JsonNode bad = JSON.readTree("{\"eventId\":\"e-2\",\"changeType\":\"CREATE\",\"eventTime\":\"SECRET-NOT-A-TIME\"}");

        assertThatThrownBy(() -> SpineEvents.encode(bad))
                .isInstanceOf(SpinePublishException.class)
                .hasMessageNotContaining("SECRET-NOT-A-TIME");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSendReturnsAfterTheBrokerAcknowledged() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<Object> acknowledged = new CompletableFuture<>();
        doReturn(acknowledged).when(kafka).send(any(String.class), any(String.class), any(String.class));
        new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            acknowledged.complete(null);
        }).start();

        long start = System.nanoTime();
        SpineEvents.sendAndAwait(kafka, "k", "{}", Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("sendAndAwait waited for the acknowledgement").isGreaterThanOrEqualTo(100);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), eq("k"), eq("{}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefusedSendIsAnExceptionWithTheBrokersErrorAsItsCause() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.failedFuture(new NetworkException("broker is down")))
                .when(kafka).send(any(String.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> SpineEvents.sendAndAwait(kafka, "k", "{}", SHORT))
                .isInstanceOf(SpinePublishException.class)
                .hasCauseInstanceOf(NetworkException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBrokerThatNeverAnswersTimesOut() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        doReturn(new CompletableFuture<>()).when(kafka).send(any(String.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> SpineEvents.sendAndAwait(kafka, "k", "{}", Duration.ofMillis(100)))
                .isInstanceOf(SpinePublishException.class)
                .hasMessageContaining("no acknowledgement");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSendThatThrowsAtOnceIsWrapped() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        doThrow(new org.apache.kafka.common.errors.TimeoutException("metadata")).when(kafka)
                .send(any(String.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> SpineEvents.sendAndAwait(kafka, "k", "{}", SHORT))
                .isInstanceOf(SpinePublishException.class)
                .hasCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
    }
}
