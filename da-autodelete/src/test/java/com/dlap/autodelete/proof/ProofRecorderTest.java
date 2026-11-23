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

package com.dlap.autodelete.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.RequestMetadata;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/**
 * RET-02: the proof turns AWAITING_PROOF into HARD_DELETED with the provider's event time, and is
 * audited; nothing else does.
 */
class ProofRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

    private final RetireDispositionRepository dispositions = mock(RetireDispositionRepository.class);
    private final SoftDeleteRecordRepository records = mock(SoftDeleteRecordRepository.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private final ProofRecorder recorder = new ProofRecorder(dispositions, records, audit, RetireProperties.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theProvidersEventMarksTheDispositionExecutedAndTheRowHardDeleted() {
        // An object-level target: the engine's own delete of one S3 object (Q-631 keeps HARD_DELETED for these).
        RetireDispositionEntity d = DeletionProofMatcherTest.disposition("AWS", "DIRECT", "ENGINE_AWS",
                "s3://dlap-aws-e2e/k/a.parquet");
        SoftDeleteRecordEntity row = SoftDeleteRecordEntity.builder().id(d.getSoftDeleteRecordId())
                .status(SoftDeleteRecordEntity.AWAITING_PROOF).nextCheckAt(NOW).build();
        Instant eventTime = Instant.parse("2026-11-02T00:41:07Z");
        DlapLogEvent event = DeletionProofMatcherTest.delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k/a.parquet",
                eventTime).setSourceService("s3lifecycle.amazonaws.com").setSourceFormat("AWS_CLOUDTRAIL/s3-file")
                .setEventId("evt-proof").build();
        when(dispositions.findByTargetUriInAndStatusIn(eq(List.of("s3://dlap-aws-e2e/k/a.parquet", "s3://dlap-aws-e2e/k",
                "s3://dlap-aws-e2e")), any())).thenReturn(List.of(d));
        when(dispositions.findByIdForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(records.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));

        assertThat(recorder.onEvent(event)).isEqualTo(1);

        assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.EXECUTED);
        assertThat(d.getProofEventId()).isEqualTo("evt-proof");
        assertThat(d.getProofEventTime()).isEqualTo(eventTime);
        assertThat(d.getProofSource()).isEqualTo("AWS_CLOUDTRAIL/s3-file");
        assertThat(row.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETED);
        assertThat(row.getHardDeletedAt()).as("business time is the provider's event time").isEqualTo(eventTime);
        verify(audit).record(eq(RetireAuditLog.EXECUTED), eq(eventTime), any(), eq("s3://dlap-aws-e2e/k/a.parquet"),
                eq(AuditOutcome.SUCCEEDED), eq(row.getId().toString()), any());
    }

    private static final Instant EVENT = Instant.parse("2026-11-02T00:41:07Z");

    /** Records the proof of one event for one disposition and returns the soft-delete row afterwards. */
    private SoftDeleteRecordEntity prove(RetireDispositionEntity d, DlapLogEvent event) {
        SoftDeleteRecordEntity row = SoftDeleteRecordEntity.builder().id(d.getSoftDeleteRecordId())
                .status(SoftDeleteRecordEntity.AWAITING_PROOF).nextCheckAt(NOW).build();
        when(dispositions.findByTargetUriInAndStatusIn(any(), any())).thenReturn(List.of(d));
        when(dispositions.findByIdForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(records.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        assertThat(recorder.onEvent(event)).isEqualTo(1);
        assertThat(d.getStatus()).as("the disposition is EXECUTED with the proof").isEqualTo(RetireDispositionEntity.EXECUTED);
        assertThat(d.getProofEventTime()).isEqualTo(EVENT);
        return row;
    }

    /**
     * Q-631: for a rule on a whole bucket the first proven child delete marks the disposition EXECUTED with that
     * proof, and the record RULE_IN_FORCE; it must not become HARD_DELETED.
     */
    @Test
    void anS3BucketRulesFirstProvenChildDeleteMarksTheRuleInForceNotTheBucketHardDeleted() {
        RetireDispositionEntity d = DeletionProofMatcherTest.disposition("AWS", "NATIVE_RULE", "S3_LIFECYCLE",
                "s3://dlap-aws-e2e");
        DlapLogEvent event = DeletionProofMatcherTest.delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k/a.parquet", EVENT)
                .setSourceService("s3lifecycle.amazonaws.com").setSourceFormat("AWS_CLOUDTRAIL/s3-file")
                .setEventId("evt-s3").build();

        SoftDeleteRecordEntity row = prove(d, event);

        assertThat(row.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_IN_FORCE);
        assertThat(row.getHardDeletedAt()).as("the bucket is not deleted").isNull();
        assertThat(row.getNextCheckAt()).isNull();
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.EXECUTED), eq(EVENT), any(), eq("s3://dlap-aws-e2e"),
                eq(AuditOutcome.SUCCEEDED), eq(row.getId().toString()), details.capture());
        assertThat(details.getValue()).containsEntry("recordState", "RULE_IN_FORCE");
    }

    @Test
    void aGcsBucketRuleAndAnAzureContainerRuleAreRulesInForceToo() {
        RetireDispositionEntity gcs = DeletionProofMatcherTest.disposition("GCP", "NATIVE_RULE", "GCS_LIFECYCLE",
                "gs://dlap-gcp-e2e");
        DlapLogEvent gcsEvent = DeletionProofMatcherTest.delete(SourceProvider.GCP, "gs://dlap-gcp-e2e/x.csv", EVENT)
                .setSourceService("storage.googleapis.com").setOperation("DELETE")
                .setRequestMetadata(RequestMetadata.newBuilder().setUserAgent("GCS Lifecycle Management")).build();
        assertThat(prove(gcs, gcsEvent).getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_IN_FORCE);

        RetireDispositionEntity azure = DeletionProofMatcherTest.disposition("AZURE", "NATIVE_RULE",
                "AZURE_BLOB_LIFECYCLE", "az://dlapazuree2e/raw");
        DlapLogEvent azureEvent = DeletionProofMatcherTest.delete(SourceProvider.AZURE, "az://dlapazuree2e/raw/a/b.csv",
                EVENT).setSourceService("blob.core.windows.net").setOperation("DeleteBlob")
                .setRequestMetadata(RequestMetadata.newBuilder().setUserAgent("ObjectLifecycleManagement")).build();
        assertThat(prove(azure, azureEvent).getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_IN_FORCE);
    }

    /** Q-631: a table expiration and an engine delete of one object remove their target: HARD_DELETED. */
    @Test
    void aTableExpirationAndAnObjectDeleteAreHardDeleted() {
        RetireDispositionEntity table = DeletionProofMatcherTest.disposition("GCP", "NATIVE_RULE",
                "BIGQUERY_TABLE_EXPIRATION", "bq://dlap-gcp-e2e/sales/orders");
        DlapLogEvent tableEvent = DeletionProofMatcherTest.delete(SourceProvider.GCP, "bq://dlap-gcp-e2e/sales/orders",
                EVENT).setSourceService("bigquery.googleapis.com").setOperation("InternalTableExpired").build();
        SoftDeleteRecordEntity tableRow = prove(table, tableEvent);
        assertThat(tableRow.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETED);
        assertThat(tableRow.getHardDeletedAt()).isEqualTo(EVENT);

        RetireDispositionEntity object = DeletionProofMatcherTest.disposition("GCP", "DIRECT", "ENGINE_GCP",
                "gs://dlap-gcp-e2e/dir/file.csv");
        DlapLogEvent objectEvent = DeletionProofMatcherTest.delete(SourceProvider.GCP, "gs://dlap-gcp-e2e/dir/file.csv",
                EVENT).setSourceService("storage.googleapis.com").setOperation("DELETE").build();
        SoftDeleteRecordEntity objectRow = prove(object, objectEvent);
        assertThat(objectRow.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETED);
        assertThat(objectRow.getHardDeletedAt()).isEqualTo(EVENT);
    }

    @Test
    void onlyContainerRulesStayInForce() {
        assertThat(NativeRulePlatform.isContainerRule("BIGQUERY_TABLE_EXPIRATION")).isFalse();
        assertThat(NativeRulePlatform.isContainerRule("ENGINE_AWS")).isFalse();
        assertThat(NativeRulePlatform.isContainerRule(null)).isFalse();
        assertThat(NativeRulePlatform.isContainerRule("S3_LIFECYCLE")).isTrue();
        assertThat(NativeRulePlatform.isContainerRule("GCS_LIFECYCLE")).isTrue();
        assertThat(NativeRulePlatform.isContainerRule("AZURE_BLOB_LIFECYCLE")).isTrue();
    }

    /**
     * RET-06 (Q-677): a container rule that waited for its proof was restored (its rule removed). A late proof of
     * what the rule deleted before proves the disposition but never moves the record out of RESTORED, RULE_ENDED
     * or RESTORE_FAILED.
     */
    @Test
    void aLateProofNeverMovesARestoredOrEndedRecordBackToRuleInForce() {
        for (String state : new String[] {SoftDeleteRecordEntity.RESTORED, SoftDeleteRecordEntity.RULE_ENDED,
                SoftDeleteRecordEntity.RESTORE_FAILED}) {
            org.mockito.Mockito.reset(audit, dispositions, records);
            RetireDispositionEntity d = DeletionProofMatcherTest.disposition("AWS", "NATIVE_RULE", "S3_LIFECYCLE",
                    "s3://dlap-aws-e2e");
            SoftDeleteRecordEntity row = SoftDeleteRecordEntity.builder().id(d.getSoftDeleteRecordId()).status(state)
                    .build();
            DlapLogEvent event = DeletionProofMatcherTest.delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k/a.parquet",
                    EVENT).setSourceService("s3lifecycle.amazonaws.com").setEventId("evt-late").build();
            when(dispositions.findByTargetUriInAndStatusIn(any(), any())).thenReturn(List.of(d));
            when(dispositions.findByIdForUpdate(d.getId())).thenReturn(Optional.of(d));
            when(records.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));

            assertThat(recorder.onEvent(event)).isEqualTo(1);

            assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.EXECUTED);
            assertThat(row.getStatus()).as(state).isEqualTo(state);
            verify(records, never()).saveAndFlush(any());
            ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
            verify(audit).record(eq(RetireAuditLog.EXECUTED), eq(EVENT), any(), any(), eq(AuditOutcome.SUCCEEDED), any(),
                    details.capture());
            assertThat(details.getValue()).containsEntry("recordState", state);
        }
    }

    @Test
    void anEventThatProvesNothingChangesNothing() {
        RetireDispositionEntity d = DeletionProofMatcherTest.disposition("AWS", "NATIVE_RULE", "S3_LIFECYCLE",
                "s3://dlap-aws-e2e");
        DlapLogEvent manual = DeletionProofMatcherTest.delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k",
                Instant.parse("2026-11-02T00:00:00Z")).setSourceService("s3.amazonaws.com").build();
        when(dispositions.findByTargetUriInAndStatusIn(any(), any())).thenReturn(List.of(d));

        assertThat(recorder.onEvent(manual)).isZero();

        assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.AWAITING_PROOF);
        verify(dispositions, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theListenerDecodesTheTypedTopicAndAcknowledgesAfterRecording() {
        DeletionProofListener listener = new DeletionProofListener(recorder);
        Acknowledgment ack = mock(Acknowledgment.class);
        DlapLogEvent event = DeletionProofMatcherTest.delete(SourceProvider.GCP, "gs://none/x",
                NOW.minus(Duration.ofHours(1))).build();
        when(dispositions.findByTargetUriInAndStatusIn(any(), any())).thenReturn(List.of());

        listener.onDeleted(record(DlapLogEventCodec.defaults().toMap(event)), ack);
        listener.onDeleted(record(null), ack);
        listener.onDeleted(null, ack);

        verify(ack, org.mockito.Mockito.times(3)).acknowledge();
    }

    @Test
    void aRecordingFailureIsNotAcknowledged() {
        DeletionProofListener listener = new DeletionProofListener(recorder);
        Acknowledgment ack = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("db")).when(dispositions).findByTargetUriInAndStatusIn(any(), any());
        DlapLogEvent event = DeletionProofMatcherTest.delete(SourceProvider.GCP, "gs://b/x", NOW).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                listener.onDeleted(record(DlapLogEventCodec.defaults().encode(event)), ack))
                .isInstanceOf(IllegalStateException.class);
        verify(ack, never()).acknowledge();
    }

    private static ConsumerRecord<String, Object> record(Object value) {
        return new ConsumerRecord<>("dlap.discovery.asset.deleted", 0, 0L, "k", value);
    }
}
