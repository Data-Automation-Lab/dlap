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

package com.dlap.autoarchival.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.executor.ArchiveRuleResetService.Outcome;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.autoarchival.rule.FakeRuleProvider;
import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditException;

/**
 * Q-669: the admin reset of a row a dead worker left in EXECUTING. Each test names one rule of the decision: it
 * reads the provider first, never applies, works only on EXECUTING, and is audit-chained.
 */
class ArchiveRuleResetServiceTest {

    private static final Instant CLAIMED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-01T14:00:00Z");
    private static final AuditActor ADMIN = AuditActor.user("admin-sub");

    private final Map<String, ArchiveRuleEntity> store = new HashMap<>();
    private final ArchiveRuleRepository rules = InMemoryRules.over(store);
    private final FakeRuleProvider gcp = new FakeRuleProvider("GCP", true);
    private final FakeRuleProvider aws = new FakeRuleProvider("AWS", true);
    private final FakeRuleProvider azure = new FakeRuleProvider("AZURE", false);
    private final ArchiveAudit audit = mock(ArchiveAudit.class);
    private final ArchiveTaskSync sync = mock(ArchiveTaskSync.class);
    private ArchiveRuleResetService service;

    @BeforeEach
    void init() {
        RulePlanner planner = new RulePlanner(List.of(gcp, aws, azure), new ArchiveRuleProperties(), rules);
        service = new ArchiveRuleResetService(rules, planner, audit, sync, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ArchiveRuleEntity executing(String provider, String mode) {
        ArchiveRuleEntity r = new ArchiveRuleEntity("dlap-archive-aaaaaaaaaaaa", "appr-1", "fire-1", "task-1", "asset-1",
                provider.equals("GCP") ? "gs://pilot/finance/" : "s3://pilot/finance/", provider, mode, "pilot",
                "finance/", "COLDLINE", 0, CLAIMED);
        r.markClaimed(CLAIMED);
        store.put(r.getRuleId(), r);
        return r;
    }

    @Test
    void aPresentRuleMakesTheRowAppliedAndNothingIsApplied() {
        ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);
        aws.rules.add(r.getRuleId());

        ArchiveRuleResetService.Result result = service.reset(r.getRuleId(), ADMIN);

        assertThat(result.outcome()).isEqualTo(Outcome.RESET_TO_APPLIED);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(r.getAppliedAt()).as("the claim time, the earliest the rule could have been written").isEqualTo(CLAIMED);
        assertThat(r.isAlreadyPresent()).as("an S3 rule carries DLAP's own id").isFalse();
        assertThat(aws.calls).containsExactly("inventory");
        assertThat(aws.calls).noneMatch(c -> c.startsWith("apply:") || c.startsWith("remove:"));
        verify(sync).sync("task-1");
    }

    @Test
    void anAbsentRuleMakesTheRowFailedWithResetRuleAbsentAndNothingIsApplied() {
        ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);

        ArchiveRuleResetService.Result result = service.reset(r.getRuleId(), ADMIN);

        assertThat(result.outcome()).isEqualTo(Outcome.RESET_TO_FAILED);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        assertThat(r.getLastError()).startsWith("RESET_RULE_ABSENT");
        assertThat(aws.calls).containsExactly("inventory");
        assertThat(aws.rules).isEmpty();
    }

    @Test
    void aPresentGcsRuleCannotBeToldFromTheCustomersSoARestoreWillLeaveIt() {
        ArchiveRuleEntity r = executing("GCP", ArchiveRuleEntity.MODE_NATIVE_RULE);
        gcp.rules.add(r.getRuleId());

        service.reset(r.getRuleId(), ADMIN);

        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(r.isAlreadyPresent()).isTrue();
    }

    @Test
    void itWorksOnlyOnARowInExecuting() {
        for (String status : List.of("PLANNED", "APPLIED", "RENDERED", "EXECUTED", "FAILED", "REFUSED", "REMOVED")) {
            ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);
            r.markFailed(status, null, CLAIMED);

            ArchiveRuleResetService.Result result = service.reset(r.getRuleId(), ADMIN);

            assertThat(result.outcome()).as(status).isEqualTo(Outcome.NOT_EXECUTING);
            assertThat(r.getStatus()).as(status).isEqualTo(status);
        }
        assertThat(aws.calls).as("the provider is not even read").isEmpty();
        verify(audit, never()).resetRequested(any(), any(), any());
    }

    @Test
    void anUnknownRuleIsNotFound() {
        assertThat(service.reset("dlap-archive-nope", ADMIN).outcome()).isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void aProviderReadFailureChangesNothing() {
        ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);
        NativeRuleProvider failing = new FakeRuleProvider("AWS", true) {
            @Override
            public Inventory inventory(RuleSpec spec) {
                throw new RuleException(RuleException.Reason.PROVIDER_ERROR, "S3 is down");
            }
        };
        RulePlanner planner = new RulePlanner(List.of(failing), new ArchiveRuleProperties(), rules);
        ArchiveRuleResetService s = new ArchiveRuleResetService(rules, planner, audit, sync, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(s.reset(r.getRuleId(), ADMIN).outcome()).isEqualTo(Outcome.PROVIDER_ERROR);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
        verify(audit, never()).resetRequested(any(), any(), any());
    }

    @Test
    void aSingleObjectArchiveAndAProviderDlapCannotReadAreRefusedNotGuessed() {
        ArchiveRuleEntity direct = executing("AWS", ArchiveRuleEntity.MODE_DIRECT_OBJECT);
        assertThat(service.reset(direct.getRuleId(), ADMIN).outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(direct.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);

        store.clear();
        ArchiveRuleEntity az = new ArchiveRuleEntity("dlap-archive-bbbbbbbbbbbb", "appr-2", null, "task-2", "asset-2",
                "az://acct/raw/", "AZURE", ArchiveRuleEntity.MODE_NATIVE_RULE, "raw", "2019/", "Cool", 30, CLAIMED);
        az.markClaimed(CLAIMED);
        store.put(az.getRuleId(), az);
        assertThat(service.reset(az.getRuleId(), ADMIN).outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(az.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
    }

    @Test
    void theAuditIntentIsAppendedBeforeTheRowChangesAndTheOutcomeAfter() {
        ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);
        aws.rules.add(r.getRuleId());
        List<String> statusAtIntent = new java.util.ArrayList<>();
        when(audit.resetRequested(any(), any(), any())).thenAnswer(inv -> {
            statusAtIntent.add(r.getStatus());
            return null;
        });

        service.reset(r.getRuleId(), ADMIN);

        assertThat(statusAtIntent).as("the row was still EXECUTING when the intent was written")
                .containsExactly(ArchiveRuleEntity.STATUS_EXECUTING);
        InOrder order = inOrder(audit, sync);
        order.verify(audit).resetRequested(eq(r), eq(ADMIN), any());
        order.verify(audit).reset(eq(r), eq(ADMIN), any());
        order.verify(sync).sync("task-1");
    }

    @Test
    void ifTheAuditLogRefusesTheIntentTheRowIsNotChanged() {
        ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);
        aws.rules.add(r.getRuleId());
        doThrow(new AuditException("audit down", null)).when(audit).resetRequested(any(), any(), any());

        ArchiveRuleResetService.Result result = service.reset(r.getRuleId(), ADMIN);

        assertThat(result.outcome()).isEqualTo(Outcome.AUDIT_UNAVAILABLE);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
        verify(audit, never()).reset(any(), any(), any());
    }

    @Test
    void aRowThatLeftExecutingWhileTheResetRanIsNotOverwritten() {
        ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);
        aws.rules.add(r.getRuleId());
        // the dead worker was not dead: it finished between the provider read and the reset
        when(audit.resetRequested(any(), any(), any())).thenAnswer(inv -> {
            r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, CLAIMED);
            return null;
        });

        ArchiveRuleResetService.Result result = service.reset(r.getRuleId(), ADMIN);

        assertThat(result.outcome()).isEqualTo(Outcome.ROW_CHANGED);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        verify(audit).resetNotApplied(r, ADMIN);
        verify(audit, never()).reset(any(), any(), any());
        verify(sync, never()).sync(any());
    }

    @Test
    void anOutcomeRecordThatCannotBeWrittenDoesNotUndoTheReset() {
        ArchiveRuleEntity r = executing("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE);
        aws.rules.add(r.getRuleId());
        doThrow(new AuditException("audit down", null)).when(audit).reset(any(), any(), any());

        assertThat(service.reset(r.getRuleId(), ADMIN).outcome()).isEqualTo(Outcome.RESET_TO_APPLIED);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }
}
