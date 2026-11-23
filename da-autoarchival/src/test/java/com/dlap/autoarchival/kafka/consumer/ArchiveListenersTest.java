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

package com.dlap.autoarchival.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.autoarchival.exception.PermanentArchivalException;
import com.dlap.autoarchival.exception.TransientArchivalException;
import com.dlap.autoarchival.proof.ArchiveProofService;
import com.dlap.autoarchival.service.IArchivalTaskService;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;

class ArchiveListenersTest {

    private final IArchivalTaskService tasks = mock(IArchivalTaskService.class);

    private static org.apache.kafka.clients.consumer.ConsumerRecord<String, Object> record(Object value) {
        return new org.apache.kafka.clients.consumer.ConsumerRecord<>("dlap.log.event.normalized", 0, 0L, "k", value);
    }
    private final Acknowledgment ack = mock(Acknowledgment.class);

    // ---- policy actions ----------------------------------------------------------------------------

    private void policyEvent(Map<String, Object> event) {
        new PolicyActionEventListener(tasks).handlePolicyActionEvent(event, "dlap.policies.actions", 0, 7L, ack);
    }

    private static Map<String, Object> archive(boolean dryRun) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", "e1");
        e.put("policyId", "p1");
        e.put("assetId", "a1");
        e.put("actionType", "ARCHIVE_ASSET");
        e.put("dryRun", dryRun);
        return e;
    }

    @Test
    void aDryRunArchivePlansAndLogsButStartsNothing() {
        policyEvent(archive(true));

        verify(tasks).logDryRun(org.mockito.ArgumentMatchers.anyMap());
        verify(tasks, never()).processArchivalAction(any());
        verify(ack).acknowledge();
    }

    @Test
    void everyOtherArchiveGoesToTheServiceWhichRefusesOneWithoutAnApproval() {
        Map<String, Object> e = archive(false);
        e.put("requireApproval", true);
        policyEvent(e);

        verify(tasks).processArchivalAction(e);
        verify(ack).acknowledge();
    }

    @Test
    void aTransientFailureIsRethrownWithoutAcknowledgingSoKafkaRetries() {
        Map<String, Object> e = archive(false);
        doThrow(new TransientArchivalException("approval could not be checked")).when(tasks).processArchivalAction(e);

        assertThatThrownBy(() -> policyEvent(e)).isInstanceOf(TransientArchivalException.class);
        verify(ack, never()).acknowledge();
    }

    @Test
    void otherActionsAndNullRecordsAreAcknowledgedAndSkipped() {
        Map<String, Object> label = archive(false);
        label.put("actionType", "addLabel");
        policyEvent(label);
        policyEvent(null);

        verifyNoInteractions(tasks);
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }

    // ---- approval decisions --------------------------------------------------------------------------

    @Test
    void aDecidedApprovalIsPassedOnAndAcknowledged() {
        new ApprovalDecidedListener(tasks).onDecided(Map.of("approvalId", "appr-1", "executable", true,
                "status", "APPROVED"), ack);
        new ApprovalDecidedListener(tasks).onDecided(Map.of("approvalId", "appr-2", "status", "REJECTED"), ack);

        verify(tasks).onApprovalDecided("appr-1", true, "APPROVED");
        verify(tasks).onApprovalDecided("appr-2", false, "REJECTED");
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }

    @Test
    void aDecisionWithoutAnApprovalIdIsIgnored() {
        new ApprovalDecidedListener(tasks).onDecided(Map.of("status", "APPROVED"), ack);
        new ApprovalDecidedListener(tasks).onDecided(null, ack);

        verify(tasks, never()).onApprovalDecided(anyString(), anyBoolean(), anyString());
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }

    // ---- the provider log ---------------------------------------------------------------------------

    @Test
    void aNormalizedEventInEitherEncodingReachesTheProofService() {
        ArchiveProofService proof = mock(ArchiveProofService.class);
        ArchiveProofListener listener = new ArchiveProofListener(proof);
        DlapLogEvent event = DlapLogEvent.newBuilder().setEventId("ev-1").setOperation("LifecycleTransition")
                .setSourceProvider(SourceProvider.AWS).setResultStatus(ResultStatus.SUCCESS).build();
        DlapLogEventCodec codec = DlapLogEventCodec.defaults();

        listener.onLogEvent(record(codec.encode(event)), ack);
        listener.onLogEvent(record(codec.toMap(event)), ack);

        org.mockito.ArgumentCaptor<DlapLogEvent> seen = org.mockito.ArgumentCaptor.forClass(DlapLogEvent.class);
        verify(proof, org.mockito.Mockito.times(2)).onEvent(seen.capture());
        assertThat(seen.getAllValues()).allSatisfy(e -> {
            assertThat(e.getEventId()).isEqualTo("ev-1");
            assertThat(e.getOperation()).isEqualTo("LifecycleTransition");
            assertThat(e.getSourceProvider()).isEqualTo(SourceProvider.AWS);
        });
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }

    @Test
    void anUndecodableRecordGoesStraightToTheDeadLetterTopic() {
        ArchiveProofService proof = mock(ArchiveProofService.class);
        ArchiveProofListener listener = new ArchiveProofListener(proof);

        assertThatThrownBy(() -> listener.onLogEvent(record("{ not json"), ack)).isInstanceOf(PermanentArchivalException.class);
        verifyNoInteractions(proof);
        verify(ack, never()).acknowledge();
    }

    @Test
    void aNullRecordIsAcknowledged() {
        ArchiveProofService proof = mock(ArchiveProofService.class);
        new ArchiveProofListener(proof).onLogEvent(record(null), ack);
        new ArchiveProofListener(proof).onLogEvent(null, ack);

        verifyNoInteractions(proof);
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }
}
