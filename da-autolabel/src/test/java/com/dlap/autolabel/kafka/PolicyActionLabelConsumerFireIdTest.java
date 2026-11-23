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

package com.dlap.autolabel.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.autolabel.service.ILabelingRunService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * IDEM-02 (Q-890): the fireId of a policy {@code addLabel} action is trimmed, a blank one is no key, and one
 * longer than 255 characters is refused and acknowledged without a retry. The service is a double here; the
 * effect on the job table is pinned by {@code PolicyActionLabelConsumerDuplicateContainerTest} on PostgreSQL.
 */
class PolicyActionLabelConsumerFireIdTest {

    private static final UUID ASSET = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private final ILabelingRunService service = mock(ILabelingRunService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final PolicyActionLabelConsumer consumer =
            new PolicyActionLabelConsumer(service, new ObjectMapper());

    private static String addLabel(String fireIdJson) {
        String fire = fireIdJson == null ? "" : "\"fireId\":" + fireIdJson + ",";
        return "{" + fire + "\"policyId\":\"p\",\"policyName\":\"P\",\"assetId\":\"" + ASSET
                + "\",\"actionType\":\"addLabel\",\"actionParameters\":{\"labelName\":\"PII\"}}";
    }

    private String appliedFireId() {
        ArgumentCaptor<String> fire = ArgumentCaptor.forClass(String.class);
        verify(service).applyPolicyLabel(eq(ASSET), eq("PII"), eq("p"), eq("P"), fire.capture());
        return fire.getValue();
    }

    @Test
    void aFireIdWithSurroundingWhitespaceIsTrimmedBeforeUse() {
        consumer.onPolicyAction(addLabel("\"  abc-123 \\t\""), ack);

        assertThat(appliedFireId()).isEqualTo("abc-123");
        verify(ack).acknowledge();
    }

    @Test
    void aBlankFireIdIsTreatedAsAbsent() {
        consumer.onPolicyAction(addLabel("\"   \""), ack);

        assertThat(appliedFireId()).isNull();
        verify(ack).acknowledge();
    }

    @Test
    void anEmptyFireIdIsTreatedAsAbsent() {
        consumer.onPolicyAction(addLabel("\"\""), ack);

        assertThat(appliedFireId()).isNull();
    }

    @Test
    void aMissingFireIdStaysAbsent() {
        consumer.onPolicyAction(addLabel(null), ack);

        assertThat(appliedFireId()).isNull();
    }

    @Test
    void aFireIdOf255CharactersIsAccepted() {
        String max = "f".repeat(255);

        consumer.onPolicyAction(addLabel("\"" + max + "\""), ack);

        assertThat(appliedFireId()).isEqualTo(max);
    }

    @Test
    void aFireIdOver255CharactersIsRefusedAcknowledgedAndNeverRetried() {
        String tooLong = "f".repeat(256);

        consumer.onPolicyAction(addLabel("\"" + tooLong + "\""), ack);

        verify(service, never()).applyPolicyLabel(any(), anyString(), any(), any(), any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void theLengthLimitAppliesAfterTrimming() {
        String max = "f".repeat(255);

        consumer.onPolicyAction(addLabel("\"  " + max + "  \""), ack);

        assertThat(appliedFireId()).isEqualTo(max);
    }

    @Test
    void aRefusedFireIdDoesNotShadowANormalActionThatFollows() {
        consumer.onPolicyAction(addLabel("\"" + "f".repeat(300) + "\""), ack);
        when(service.applyPolicyLabel(any(), anyString(), any(), any(), any())).thenReturn("job-1");

        consumer.onPolicyAction(addLabel("\"ok\""), ack);

        verify(service, times(1)).applyPolicyLabel(eq(ASSET), eq("PII"), eq("p"), eq("P"), eq("ok"));
        verify(ack, times(2)).acknowledge();
    }
}
