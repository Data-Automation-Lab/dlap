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

package com.dlap.reporting.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.reporting.dto.NotificationRequestDTO;
import com.dlap.reporting.model.NotificationChannel;
import com.dlap.reporting.service.INotificationService;
import com.dlap.reporting.service.PolicyActionEventRecorder;

/**
 * A null policy action event is acknowledged and skipped: nothing is notified or
 * recorded, and the record is not redelivered. IDEM-01 (Q-855): a repeated {@code fireId} is recorded and
 * notified once and still acknowledged; the database side is covered by
 * {@code PolicyActionEventRecorderDataJpaTest} and {@code PolicyActionConsumerDuplicateContainerTest}.
 */
class PolicyActionConsumerTest {

    @Test
    void aNullEventIsAcknowledgedAndSkipped() {
        INotificationService notifications = mock(INotificationService.class);
        PolicyActionEventRecorder recorder = mock(PolicyActionEventRecorder.class);
        Acknowledgment ack = mock(Acknowledgment.class);

        new PolicyActionConsumer(notifications, recorder).handlePolicyActionEvent(null, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(notifications, recorder);
    }

    private static Map<String, Object> notify(String fireId) {
        Map<String, Object> e = new HashMap<>();
        e.put("policyId", "p1");
        e.put("assetId", "a1");
        e.put("actionType", "NOTIFY");
        e.put("evaluationId", "ev-1");
        e.put("eventTimestamp", "2026-11-02T10:00:00Z");
        e.put("channels", List.of("email"));
        e.put("notificationTypeKey", "PII_ALERT");
        if (fireId != null) {
            e.put("fireId", fireId);
        }
        return e;
    }

    /** A recorder over an in-memory set of fire ids: the unique index of {@code fire_id}, without a database. */
    private static PolicyActionEventRecorder recorderOver(Set<String> fireIds, List<String> rows) {
        PolicyActionEventRecorder recorder = mock(PolicyActionEventRecorder.class);
        when(recorder.isRecorded(anyString())).thenAnswer(inv -> fireIds.contains(inv.<String>getArgument(0)));
        when(recorder.recordOnce(any(), any())).thenAnswer(inv -> {
            Map<String, Object> event = inv.getArgument(0);
            String fireId = PolicyActionEventRecorder.fireIdOf(event);
            if (fireId != null && !fireIds.add(fireId)) {
                return false;
            }
            inv.<BooleanSupplier>getArgument(1).getAsBoolean();
            rows.add(fireId);
            return true;
        });
        return recorder;
    }

    @Test
    void aRepeatedFireIdSendsOneNotificationAndStoresOneRowButIsAcknowledgedEveryTime() {
        INotificationService notifications = mock(INotificationService.class);
        List<String> rows = new ArrayList<>();
        PolicyActionConsumer consumer = new PolicyActionConsumer(notifications, recorderOver(new HashSet<>(), rows));
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handlePolicyActionEvent(notify("fire-1"), ack);
        consumer.handlePolicyActionEvent(notify("fire-1"), ack);
        consumer.handlePolicyActionEvent(notify("fire-2"), ack);

        ArgumentCaptor<NotificationRequestDTO> sent = ArgumentCaptor.forClass(NotificationRequestDTO.class);
        verify(notifications, times(2)).sendNotification(sent.capture());
        assertThat(rows).containsExactly("fire-1", "fire-2");
        NotificationRequestDTO first = sent.getAllValues().get(0);
        assertThat(first.getNotificationTypeKey()).isEqualTo("PII_ALERT");
        assertThat(first.getChannels()).containsExactly(NotificationChannel.EMAIL);
        assertThat(first.getSubject()).contains("a1").contains("p1");
        verify(ack, times(3)).acknowledge();
    }

    @Test
    void aCopyThatLosesTheInsertRaceIsTheDuplicateAndIsAcknowledged() {
        INotificationService notifications = mock(INotificationService.class);
        PolicyActionEventRecorder recorder = mock(PolicyActionEventRecorder.class);
        when(recorder.recordOnce(any(), any())).thenThrow(new DataIntegrityViolationException("ux_fire_id"));
        when(recorder.isRecorded("fire-1")).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        new PolicyActionConsumer(notifications, recorder).handlePolicyActionEvent(notify("fire-1"), ack);

        verify(ack).acknowledge();
        verifyNoInteractions(notifications);
    }

    @Test
    void anIntegrityViolationThatIsNotADuplicateFireIsNotAcknowledged() {
        PolicyActionEventRecorder recorder = mock(PolicyActionEventRecorder.class);
        when(recorder.recordOnce(any(), any())).thenThrow(new DataIntegrityViolationException("too long"));
        when(recorder.isRecorded("fire-1")).thenReturn(false);
        Acknowledgment ack = mock(Acknowledgment.class);
        PolicyActionConsumer consumer = new PolicyActionConsumer(mock(INotificationService.class), recorder);

        assertThatThrownBy(() -> consumer.handlePolicyActionEvent(notify("fire-1"), ack))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> consumer.handlePolicyActionEvent(notify(null), ack))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(ack, never()).acknowledge();
    }

    @Test
    void aFailedRecordIsNotAcknowledgedSoTheRecordIsRedelivered() {
        PolicyActionEventRecorder recorder = mock(PolicyActionEventRecorder.class);
        when(recorder.recordOnce(any(), any())).thenThrow(new IllegalStateException("database down"));
        Acknowledgment ack = mock(Acknowledgment.class);
        PolicyActionConsumer consumer = new PolicyActionConsumer(mock(INotificationService.class), recorder);

        assertThatThrownBy(() -> consumer.handlePolicyActionEvent(notify("fire-1"), ack))
                .isInstanceOf(IllegalStateException.class);
        verify(ack, never()).acknowledge();
    }

    @Test
    void anActionThatIsNotANotifyDispatchesNothing() {
        INotificationService notifications = mock(INotificationService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        Map<String, Object> label = notify("fire-9");
        label.put("actionType", "addLabel");

        new PolicyActionConsumer(notifications, recorderOver(new HashSet<>(), new ArrayList<>()))
                .handlePolicyActionEvent(label, ack);

        verifyNoInteractions(notifications);
        verify(ack).acknowledge();
    }

    @Test
    void aMalformedChannelListFallsBackToEmailAndSlack() {
        INotificationService notifications = mock(INotificationService.class);
        Map<String, Object> event = notify("fire-3");
        event.put("channels", List.of("carrier-pigeon"));
        event.remove("notificationTypeKey");

        new PolicyActionConsumer(notifications, recorderOver(new HashSet<>(), new ArrayList<>()))
                .handlePolicyActionEvent(event, mock(Acknowledgment.class));

        ArgumentCaptor<NotificationRequestDTO> sent = ArgumentCaptor.forClass(NotificationRequestDTO.class);
        verify(notifications).sendNotification(sent.capture());
        assertThat(sent.getValue().getChannels())
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.SLACK);
        assertThat(sent.getValue().getNotificationTypeKey()).isEqualTo("POLICY_ALERT");
    }
}
