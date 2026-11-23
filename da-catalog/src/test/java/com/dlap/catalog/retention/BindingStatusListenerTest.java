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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;
import com.dlap.proto.log.v1.BindingScope;
import com.dlap.proto.log.v1.BindingState;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.ReadCoverage;
import com.dlap.proto.log.v1.SourceProvider;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The binding-status consumer reads proto3 JSON (the form da-logingest publishes), keeps the
 * latest status per binding, treats a tombstone as the end of the binding, and sends anything
 * that is not a status to the error handler.
 */
class BindingStatusListenerTest {

    private static final Instant REPORTED = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LOW = Instant.parse("2026-10-20T09:00:00Z");

    private final BindingStatusStore store = mock(BindingStatusStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BindingStatusListener listener = new BindingStatusListener(store, new RetentionMetrics(registry));

    private static Timestamp ts(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    private static String json(LogBindingStatus status) throws Exception {
        return JsonFormat.printer().omittingInsignificantWhitespace().print(status);
    }

    private static LogBindingStatus.Builder status() {
        return LogBindingStatus.newBuilder()
                .setBindingId("gcp-audit-proj-a")
                .setSourceProvider(SourceProvider.GCP)
                .setSourceKind("GCP_CLOUD_AUDIT")
                .setScope(BindingScope.newBuilder().setScopeType("GCP_PROJECT").setScopeId("proj-a"))
                .setState(BindingState.BINDING_STATE_ACTIVE)
                .setConfigHash("abc123")
                .setReportedTime(ts(REPORTED))
                .setLowWatermark(ts(LOW))
                .setHighWatermark(ts(LOW.plusSeconds(60)))
                .setReadCoverage(ReadCoverage.READ_COVERAGE_CAPTURED)
                .setReadCoverageSince(ts(SINCE))
                .setSchemaVersion("v1");
    }

    private double count(String outcome) {
        var c = registry.find(RetentionMetrics.BINDING_STATUS).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void storesTheStatusWithEveryFieldTheEvaluatorNeeds() throws Exception {
        when(store.upsert(any(), any())).thenReturn(true);

        listener.apply(json(status().build()), "gcp-audit-proj-a");

        ArgumentCaptor<BindingStatus> row = ArgumentCaptor.forClass(BindingStatus.class);
        verify(store).upsert(row.capture(), eq("abc123"));
        assertThat(row.getValue()).isEqualTo(new BindingStatus("gcp-audit-proj-a", "GCP", "GCP_CLOUD_AUDIT",
                "GCP_PROJECT", "proj-a", "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED", SINCE, LOW,
                LOW.plusSeconds(60), REPORTED));
        assertThat(count("stored")).isEqualTo(1);
    }

    @Test
    void aStatusReportedEarlierThanTheStoredOneIsCountedAsStale() throws Exception {
        when(store.upsert(any(), any())).thenReturn(false);

        listener.apply(json(status().build()), "gcp-audit-proj-a");

        assertThat(count("stale")).isEqualTo(1);
        assertThat(count("stored")).isZero();
    }

    @Test
    void theRecordKeyIsTheBindingIdWhenThePayloadHasNone() throws Exception {
        when(store.upsert(any(), any())).thenReturn(true);

        listener.apply(json(status().clearBindingId().build()), "from-the-key");

        ArgumentCaptor<BindingStatus> row = ArgumentCaptor.forClass(BindingStatus.class);
        verify(store).upsert(row.capture(), any());
        assertThat(row.getValue().bindingId()).isEqualTo("from-the-key");
    }

    @Test
    void anAbsentWatermarkOrStartStaysNullSoTheEvaluatorTreatsItAsNoCoverage() throws Exception {
        when(store.upsert(any(), any())).thenReturn(true);

        listener.apply(json(status().clearLowWatermark().clearReadCoverageSince().build()), "k");

        ArgumentCaptor<BindingStatus> row = ArgumentCaptor.forClass(BindingStatus.class);
        verify(store).upsert(row.capture(), any());
        assertThat(row.getValue().lowWatermark()).isNull();
        assertThat(row.getValue().readCoverageSince()).isNull();
    }

    @Test
    void unknownFieldsFromANewerProducerAreIgnored() throws Exception {
        when(store.upsert(any(), any())).thenReturn(true);
        String payload = json(status().build()).replace("\"schemaVersion\":\"v1\"",
                "\"schemaVersion\":\"v1\",\"addedLater\":{\"x\":1}");

        listener.apply(payload, "k");

        verify(store).upsert(any(), any());
    }

    @Test
    void aTombstoneRemovesTheBindingSoItsCoverageStopsCounting() {
        when(store.delete("gcp-audit-proj-a")).thenReturn(true);

        listener.apply(null, "gcp-audit-proj-a");

        verify(store).delete("gcp-audit-proj-a");
        verify(store, never()).upsert(any(), any());
        assertThat(count("removed")).isEqualTo(1);
    }

    @Test
    void aTombstoneWithoutAKeyChangesNothing() {
        listener.apply(null, null);

        verify(store, never()).delete(any());
        assertThat(count("ignored")).isEqualTo(1);
    }

    @Test
    void aPayloadThatIsNotAStatusIsRejectedForTheErrorHandler() {
        assertThatThrownBy(() -> listener.apply("not json at all", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.apply("[1,2]", "k")).isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).upsert(any(), any());
    }

    @Test
    void aStatusWithoutAReportedTimeOrAnIdIsRejected() throws Exception {
        assertThatThrownBy(() -> listener.apply(json(status().clearReportedTime().build()), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reported time");
        assertThatThrownBy(() -> listener.apply(json(status().clearBindingId().build()), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding id");
    }

    @Test
    void theListenerAcknowledgesAfterApplyingAndNotWhenItFails() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        when(store.upsert(any(), any())).thenReturn(true);

        listener.onBindingStatus(json(status().build()), "k", ack);
        verify(ack).acknowledge();

        Acknowledgment failed = mock(Acknowledgment.class);
        assertThatThrownBy(() -> listener.onBindingStatus("garbage", "k", failed))
                .isInstanceOf(IllegalArgumentException.class);
        verify(failed, never()).acknowledge();
    }
}
