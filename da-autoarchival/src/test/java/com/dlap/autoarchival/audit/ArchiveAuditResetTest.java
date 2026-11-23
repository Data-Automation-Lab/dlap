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

package com.dlap.autoarchival.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditStreams;

/**
 * Q-669: the three audit records of a reset. Each names its event type, outcome and idempotency key, and goes to
 * the {@code retire.archive} stream with the administrator as the actor.
 */
class ArchiveAuditResetTest {

    private static final Instant NOW = Instant.parse("2026-11-01T14:00:00Z");
    private static final AuditActor ADMIN = AuditActor.user("admin-sub");

    private final AuditLogWriter writer = mock(AuditLogWriter.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuditLogWriter> provider = mock(ObjectProvider.class);
    private ArchiveAudit audit;
    private ArchiveRuleEntity rule;

    @BeforeEach
    void init() {
        when(provider.getIfAvailable()).thenReturn(writer);
        audit = new ArchiveAudit(provider, Clock.fixed(NOW, ZoneOffset.UTC));
        rule = new ArchiveRuleEntity("dlap-archive-aaaaaaaaaaaa", "appr-1", null, "task-1", "asset-1",
                "gs://pilot/finance/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "COLDLINE", 0,
                NOW);
        rule.markClaimed(NOW);
    }

    private AuditEvent captured() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer).append(eq(AuditStreams.RETIRE_ARCHIVE), event.capture());
        return event.getValue();
    }

    @Test
    void theIntentIsARequestedRecordKeyedByTheApprovalAndCarriesWhatTheProviderShowed() {
        audit.resetRequested(rule, ADMIN, Map.of("providerRulePresent", true, "newStatus", "APPLIED"));

        AuditEvent e = captured();
        assertThat(e.eventType()).isEqualTo("retire.archive.reset_requested");
        assertThat(e.outcome()).isEqualTo(AuditOutcome.REQUESTED);
        assertThat(e.actor()).isEqualTo(ADMIN);
        assertThat(e.correlationId()).isEqualTo("appr-1");
        assertThat(e.idempotencyKey()).isEqualTo("appr-1:reset-requested");
        assertThat(e.occurredAt()).isEqualTo(NOW);
        assertThat(e.details()).containsEntry("providerRulePresent", true).containsEntry("newStatus", "APPLIED")
                .containsEntry("ruleId", "dlap-archive-aaaaaaaaaaaa");
    }

    @Test
    void theOutcomeIsASucceededRecordWithItsOwnKey() {
        audit.reset(rule, ADMIN, Map.of("reason", "RESET_RULE_ABSENT"));

        AuditEvent e = captured();
        assertThat(e.eventType()).isEqualTo("retire.archive.rule_reset");
        assertThat(e.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(e.idempotencyKey()).isEqualTo("appr-1:reset");
        assertThat(e.details()).containsEntry("reason", "RESET_RULE_ABSENT");
    }

    @Test
    void aResetThatChangedNothingIsAFailedRecordNamingRowChanged() {
        audit.resetNotApplied(rule, ADMIN);

        AuditEvent e = captured();
        assertThat(e.eventType()).isEqualTo("retire.archive.rule_reset");
        assertThat(e.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(e.idempotencyKey()).isEqualTo("appr-1:reset-not-applied");
        assertThat(e.details()).containsEntry("reason", "ROW_CHANGED").containsEntry("status", "EXECUTING");
        verify(writer).append(any(), any());
    }
}
