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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.dlap.autoarchival.approval.ApprovalRefusedException;
import com.dlap.autoarchival.approval.ApprovalRefusedException.Reason;
import com.dlap.autoarchival.approval.ApprovalVerifier;
import com.dlap.autoarchival.approval.ArchiveApproval;
import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.autoarchival.rule.FakeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.autoarchival.service.storage.ArchivalResult;
import com.dlap.autoarchival.service.storage.ICloudStorageService;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditException;

class ArchiveExecutorTest {

    private static final String APPROVAL = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AuditActor SERVICE = AuditActor.service("da-autoarchival");

    private final Map<String, ArchiveRuleEntity> store = new HashMap<>();
    private final ArchiveRuleRepository rules = mock(ArchiveRuleRepository.class);
    private final FakeRuleProvider gcp = new FakeRuleProvider("GCP", true);
    private final FakeRuleProvider azure = new FakeRuleProvider("AZURE", false);
    private final FakeRuleProvider aws = new FakeRuleProvider("AWS", true);
    private final ApprovalVerifier verifier = mock(ApprovalVerifier.class);
    private final ArchiveAudit audit = mock(ArchiveAudit.class);
    private final ICloudStorageService storage = mock(ICloudStorageService.class);
    private final IArchivalConfigService config = configWith("COLD");
    private ArchiveExecutor executor;

    private static IArchivalConfigService configWith(String defaultTier) {
        IArchivalConfigService c = mock(IArchivalConfigService.class);
        when(c.getArchivalConfig()).thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier(defaultTier).build());
        return c;
    }

    @BeforeEach
    void init() {
        when(rules.findByApprovalId(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(r -> r.getApprovalId().equals(inv.getArgument(0))).findFirst());
        when(rules.findByFireId(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(r -> inv.getArgument(0).equals(r.getFireId())).findFirst());
        when(rules.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        when(rules.claimPlanned(anyString(), any(Instant.class))).thenAnswer(inv -> {
            ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
            if (r == null || !ArchiveRuleEntity.STATUS_PLANNED.equals(r.getStatus())) {
                return 0;
            }
            r.markClaimed(inv.getArgument(1));
            return 1;
        });
        when(rules.endIfPlanned(anyString(), anyString(), any(), any(Instant.class))).thenAnswer(inv -> {
            ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
            if (r == null || !ArchiveRuleEntity.STATUS_PLANNED.equals(r.getStatus())) {
                return 0;
            }
            r.markFailed(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
            return 1;
        });
        // Q-712: the claimer's own outcome writes are conditional on EXECUTING, as in the database.
        when(rules.finishIfExecuting(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(Instant.class))).thenAnswer(inv -> {
                    ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                    if (r == null || !ArchiveRuleEntity.STATUS_EXECUTING.equals(r.getStatus())) {
                        return 0;
                    }
                    r.markApplied(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                            inv.getArgument(5));
                    return 1;
                });
        when(rules.failIfExecuting(anyString(), any(), any(Instant.class))).thenAnswer(inv -> {
            ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
            if (r == null || !ArchiveRuleEntity.STATUS_EXECUTING.equals(r.getStatus())) {
                return 0;
            }
            r.markFailed(ArchiveRuleEntity.STATUS_FAILED, inv.getArgument(1), inv.getArgument(2));
            return 1;
        });
        when(rules.saveAndFlush(any())).thenAnswer(inv -> {
            ArchiveRuleEntity r = inv.getArgument(0);
            store.put(r.getRuleId(), r);
            return r;
        });
        when(rules.save(any())).thenAnswer(inv -> {
            ArchiveRuleEntity r = inv.getArgument(0);
            store.put(r.getRuleId(), r);
            return r;
        });
        RulePlanner planner = new RulePlanner(List.of(gcp, azure, aws), new ArchiveRuleProperties(), rules);
        executor = new ArchiveExecutor(planner, rules, verifier, audit, CLOCK, List.of(storage), config);
        // The policy engine's answer describes exactly what was approved: the place the stored row targets.
        when(verifier.verifyById(APPROVAL, ASSET)).thenAnswer(inv -> approvalFor(only(), Map.of("tier", "COLD")));
        when(storage.supportsCloudProvider(anyString())).thenReturn(true);
        // The intent record is part of the ordered call log so a test can prove it came before the write.
        when(audit.requested(any(), any(), any())).thenAnswer(inv -> {
            gcp.calls.add("audit.requested");
            aws.calls.add("audit.requested");
            return null;
        });
    }

    private static ArchiveExecutor.Request request(String provider, String uri) {
        return new ArchiveExecutor.Request("task-1", ASSET, uri, provider, Map.of("tier", "COLD"), null, APPROVAL,
                "fire-1");
    }

    private static ArchiveApproval approvalOf(String provider, String uri, Map<String, Object> parameters) {
        return new ArchiveApproval(APPROVAL, "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false, uri, provider,
                parameters);
    }

    private static ArchiveApproval approvalFor(ArchiveRuleEntity row, Map<String, Object> parameters) {
        return approvalOf(row.getProvider(), row.getAssetUri(), parameters);
    }

    private ArchiveRuleEntity only() {
        assertThat(store).hasSize(1);
        return store.values().iterator().next();
    }

    // ---- the approval binds the place and the rule, not only the asset id ----------------------

    private void approvalIs(String provider, String uri, Map<String, Object> parameters) {
        when(verifier.verifyById(APPROVAL, ASSET)).thenReturn(approvalOf(provider, uri, parameters));
    }

    private void assertRefusedAsMismatchAndNothingHappened(ArchiveRuleEntity row) {
        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("ASSET_MISMATCH");
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:") || c.equals("audit.requested"));
        assertThat(gcp.rules).isEmpty();
        verify(audit).refused(eq(APPROVAL), anyString(), eq("ASSET_MISMATCH"), anyString(), eq(APPROVAL), eq("task-1"));
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void anApprovalGivenForAnotherBucketIsRefusedWhenTheEventPointsElsewhere() {
        // a forged event: the real approval id and asset id, but another bucket in the snapshot
        executor.prepare(request("GCP", "gs://other-bucket/"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"));

        assertRefusedAsMismatchAndNothingHappened(executor.execute(APPROVAL, SERVICE));
    }

    @Test
    void anApprovalForAPrefixIsRefusedWhenTheEventWidensItToTheWholeBucket() {
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/", "GCP",
                Map.of("tier", "COLD", "prefix", ""), null, APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/", Map.of("tier", "COLD", "prefix", "finance/"));

        assertRefusedAsMismatchAndNothingHappened(executor.execute(APPROVAL, SERVICE));
    }

    @Test
    void anApprovalForOnePrefixIsRefusedWhenTheEventNamesAnother() {
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/", "GCP",
                Map.of("tier", "COLD", "prefix", "hr/"), null, APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/", Map.of("tier", "COLD", "prefix", "finance/"));

        assertRefusedAsMismatchAndNothingHappened(executor.execute(APPROVAL, SERVICE));
    }

    @Test
    void anApprovalForAFolderIsRefusedWhenTheEventTargetsOneObjectOfIt() {
        executor.prepare(request("GCP", "gs://pilot/finance/q1.parquet"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("ASSET_MISMATCH");
        verify(storage, never()).archiveData(any(), anyString(), anyString(), any());
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void anApprovalForAnotherProviderIsRefused() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        approvalIs("AWS", "s3://pilot/finance/", Map.of("tier", "COLD"));

        assertRefusedAsMismatchAndNothingHappened(executor.execute(APPROVAL, SERVICE));
    }

    @Test
    void anApprovalGivenForAnotherAzureAccountIsRefused() {
        executor.prepare(request("AZURE", "az://acct/raw/2019/"));
        azure.calls.clear();
        approvalIs("AZURE", "az://otheracct/raw/2019/", Map.of("tier", "COLD"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("ASSET_MISMATCH");
        assertThat(azure.calls).noneMatch(c -> c.startsWith("apply:"));
    }

    @Test
    void anApprovalForAColdTierIsRefusedWhenTheEventAsksForTheArchiveTier() {
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "ARCHIVE"), null, APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("ASSET_MISMATCH").contains("tier");
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void anApprovalForAnAgeIsRefusedWhenTheEventAsksForAnother() {
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "COLD", "afterDays", 400), null, APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of("tier", "COLD", "afterDays", "30"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("ASSET_MISMATCH").contains("days");
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
    }

    @Test
    void anApprovalSilentOnTheAgeIsRefusedWhenTheEventAddsOne() {
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "COLD", "afterDays", 400), null, APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"));

        assertThat(executor.execute(APPROVAL, SERVICE).getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
    }

    // ---- an approval silent on the tier is bound to the configured default tier ----------------

    @Test
    void anApprovalSilentOnTheTierIsRefusedWhenTheEventAsksForTheArchiveTierInsteadOfTheDefault() {
        // a forged event adds tier ARCHIVE; the approval names none, so it covers only the default (COLD)
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "ARCHIVE"), null, APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of());

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertRefusedAsMismatchAndNothingHappened(row);
        assertThat(row.getLastError()).contains("default tier COLDLINE");
    }

    @Test
    void anApprovalSilentOnTheTierOnAwsIsRefusedWhenTheRowWasPlannedWithDeepArchive() {
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "s3://pilot/finance/", "AWS",
                Map.of("tier", "ARCHIVE"), null, APPROVAL, "fire-1"));
        aws.calls.clear();
        assertThat(only().getNativeClass()).isEqualTo("DEEP_ARCHIVE");
        approvalIs("AWS", "s3://pilot/finance/", Map.of());

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("ASSET_MISMATCH").contains("default tier");
        assertThat(aws.calls).noneMatch(c -> c.startsWith("apply:") || c.equals("audit.requested"));
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void anApprovalSilentOnTheTierStillRunsWhenTheRowUsesTheDefaultTier() {
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of(), "COLD", APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of());

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(gcp.rules).hasSize(1);
        verify(audit).requested(any(), any(), any());
    }

    @Test
    void aDefaultTierThatChangedSincePlanningFailsClosed() {
        IArchivalConfigService changing = mock(IArchivalConfigService.class);
        when(changing.getArchivalConfig()).thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier("COLD").build());
        RulePlanner planner = new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules);
        ArchiveExecutor late = new ArchiveExecutor(planner, rules, verifier, audit, CLOCK, List.of(), changing);
        late.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP", Map.of(), "COLD",
                APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of());
        when(changing.getArchivalConfig()).thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier("ARCHIVE").build());

        assertThat(late.execute(APPROVAL, SERVICE).getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
    }

    @Test
    void anUnreadableConfigurationFallsBackToTheBuiltInDefaultAndStillBindsTheTier() {
        IArchivalConfigService broken = mock(IArchivalConfigService.class);
        when(broken.getArchivalConfig()).thenThrow(new IllegalStateException("db down"));
        RulePlanner planner = new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules);
        ArchiveExecutor e = new ArchiveExecutor(planner, rules, verifier, audit, CLOCK, List.of(), broken);
        e.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "ARCHIVE"), null, APPROVAL, "fire-1"));
        gcp.calls.clear();
        approvalIs("GCP", "gs://pilot/finance/", Map.of());

        assertThat(e.execute(APPROVAL, SERVICE).getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
    }

    @Test
    void anApprovalThatNamesNoLocationCannotBeBoundAndIsRefused() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        approvalIs(null, null, Map.of("tier", "COLD"));

        assertRefusedAsMismatchAndNothingHappened(executor.execute(APPROVAL, SERVICE));
    }

    @Test
    void anApprovalWithAnUnreadableLocationIsRefused() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        approvalIs("GCP", "bq://proj/ds.tbl", Map.of("tier", "COLD"));

        assertRefusedAsMismatchAndNothingHappened(executor.execute(APPROVAL, SERVICE));
    }

    @Test
    void anApprovalForTheSamePlaceWrittenDifferentlyStillRuns() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        approvalIs("gcp", "GS://pilot/", Map.of("targetStorageClass", "COLDLINE", "prefix", "finance/"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(gcp.rules).containsExactly(row.getRuleId());
    }

    // ---- prepare -------------------------------------------------------------------------------

    @Test
    void prepareStoresAPlannedRowAndTouchesNothingAtTheProviderExceptRead() {
        ArchiveRuleEntity row = executor.prepare(request("GCP", "gs://pilot/finance/"));

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_PLANNED);
        assertThat(row.getMode()).isEqualTo(ArchiveRuleEntity.MODE_NATIVE_RULE);
        assertThat(row.getApprovalId()).isEqualTo(APPROVAL);
        assertThat(row.getFireId()).isEqualTo("fire-1");
        assertThat(row.getScopeBucket()).isEqualTo("pilot");
        assertThat(row.getScopePrefix()).isEqualTo("finance/");
        assertThat(row.getNativeClass()).isEqualTo("COLDLINE");
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(gcp.calls).containsExactly("inventory");
        assertThat(gcp.rules).isEmpty();
    }

    @Test
    void aRepeatedApprovalOrFireIsTheSameDecisionAndPlansNothingAgain() {
        ArchiveRuleEntity first = executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();

        ArchiveRuleEntity second = executor.prepare(request("GCP", "gs://pilot/finance/"));
        ArchiveRuleEntity sameFire = executor.prepare(new ArchiveExecutor.Request("task-9", ASSET,
                "gs://pilot/finance/", "GCP", Map.of(), null, "another-approval", "fire-1"));

        assertThat(second).isSameAs(first);
        assertThat(sameFire).isSameAs(first);
        assertThat(gcp.calls).isEmpty();
        assertThat(store).hasSize(1);
    }

    @Test
    void aRaceOnTheUniqueApprovalIdReturnsTheRowTheWinnerStored() {
        ArchiveRuleEntity winner = new ArchiveRuleEntity("dlap-archive-winner", APPROVAL, "fire-1", "task-0", ASSET,
                "gs://pilot/finance/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "COLDLINE", 0,
                NOW);
        when(rules.findByApprovalId(APPROVAL)).thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        doThrow(new DataIntegrityViolationException("ux_archive_rules_approval")).when(rules).saveAndFlush(any());

        assertThat(executor.prepare(request("GCP", "gs://pilot/finance/"))).isSameAs(winner);
    }

    @Test
    void aRefusedPlanStoresNoRowAndSaysWhy() {
        assertThatThrownBy(() -> executor.prepare(new ArchiveExecutor.Request("t", ASSET, "gs://pilot/", "GCP",
                Map.of("tier", "STANDARD_ARCHIVE"), null, APPROVAL, null))).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.INVALID_TIER));
        assertThatThrownBy(() -> executor.prepare(request("GCP", "bq://proj/ds.tbl")))
                .isInstanceOf(RuleException.class);
        assertThat(store).isEmpty();
    }

    @Test
    void aSingleObjectIsPlannedAsADirectChange() {
        ArchiveRuleEntity row = executor.prepare(request("AWS", "s3://pilot/finance/q1.parquet"));

        assertThat(row.getMode()).isEqualTo(ArchiveRuleEntity.MODE_DIRECT_OBJECT);
        assertThat(row.getScopePrefix()).isEqualTo("finance/q1.parquet");
        assertThat(row.getNativeClass()).isEqualTo("GLACIER_IR");
    }

    // ---- execute: the section 9 rules and the audit order -----------------------------------

    @Test
    void anApprovedRuleIsAppliedAfterTheIntentRecordAndNeverMarkedExecuted() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(row.getStatus()).isNotEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);
        assertThat(row.getExecutedAt()).isNull();
        assertThat(row.getEvidenceEventId()).isNull();
        assertThat(row.getAppliedAt()).isEqualTo(NOW);
        assertThat(row.getRuleDocument()).contains(row.getRuleId());
        assertThat(row.isMock()).isFalse();
        assertThat(gcp.rules).containsExactly(row.getRuleId());
        // rule 3 (approval re-read), then the REQUESTED record, then the write, then the outcome record
        assertThat(gcp.calls).containsExactly("inventory", "audit.requested", "apply:" + row.getRuleId());
        verify(verifier).verifyById(APPROVAL, ASSET);
        verify(audit).requested(eq(row), any(), eq(SERVICE));
        verify(audit).applied(eq(row), any(), any());
        verify(audit, never()).executed(any(), any());
    }

    @Test
    void ifTheAuditLogRefusesTheIntentRecordTheProviderIsNeverCalled() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        when(audit.requested(any(), any(), any())).thenThrow(new AuditException("audit database down", null));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        assertThat(row.getLastError()).contains("audit log is unavailable").contains("nothing was changed");
        assertThat(gcp.calls).doesNotContain("apply:" + row.getRuleId());
        assertThat(gcp.rules).isEmpty();
        verify(audit, never()).applied(any(), any(), any());
    }

    @Test
    void anApprovalThatIsRejectedOrExpiredIsRefusedAndAuditedNeverApplied() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        when(verifier.verifyById(APPROVAL, ASSET)).thenThrow(
                new ApprovalRefusedException(Reason.NOT_EXECUTABLE, "approval is REJECTED and not executable"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("NOT_EXECUTABLE");
        assertThat(gcp.calls).isEmpty();
        verify(audit).refused(eq(APPROVAL), anyString(), eq("NOT_EXECUTABLE"), anyString(), eq(APPROVAL), eq("task-1"));
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void anApprovalThatIsStillPendingWaitsWithoutAnyRecordOrCall() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        when(verifier.verifyById(APPROVAL, ASSET)).thenThrow(
                new ApprovalRefusedException(Reason.NOT_DECIDED, "still waiting"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_PLANNED);
        assertThat(gcp.calls).isEmpty();
        verify(audit, never()).refused(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anApprovalThatCannotBeCheckedIsTransientAndTheRowStaysPlanned() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        when(verifier.verifyById(APPROVAL, ASSET)).thenThrow(
                new ApprovalRefusedException(Reason.VERIFY_UNAVAILABLE, "the policy engine could not be reached"));

        assertThatThrownBy(() -> executor.execute(APPROVAL, SERVICE)).isInstanceOfSatisfying(
                ApprovalRefusedException.class, e -> assertThat(e.isTransient()).isTrue());

        assertThat(only().getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_PLANNED);
        assertThat(only().getLastError()).contains("could not be checked");
        assertThat(gcp.calls).isEmpty();
    }

    @Test
    void aBucketThatFilledUpSinceTheApprovalIsRefusedBeforeTheIntentRecord() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        gcp.foreignRules = 100;

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(row.getLastError()).startsWith("RULE_LIMIT");
        assertThat(gcp.calls).containsExactly("inventory");
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void aProviderFailureAfterTheIntentIsRecordedAsFailedWithItsReason() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.applyFailure = new RuleException(RuleException.Reason.VERIFY_FAILED, "rule missing after the write");

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        assertThat(row.getLastError()).startsWith("VERIFY_FAILED");
        verify(audit).requested(any(), any(), any());
        verify(audit).failed(eq(row), any(), eq("VERIFY_FAILED"));
        verify(audit, never()).applied(any(), any(), any());
    }

    @Test
    void anUnexpectedExceptionIsAlsoAFailureNotACrash() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.applyFailure = new IllegalStateException("boom");

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        verify(audit).failed(eq(row), any(), eq("UNEXPECTED"));
    }

    @Test
    void aProviderWithoutAnApplyApiEndsRenderedAndSaysSo() {
        executor.prepare(request("AZURE", "az://acct/raw/2019/"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_RENDERED);
        assertThat(row.getRuleDocument()).contains(row.getRuleId());
        verify(audit).rendered(eq(row), any());
        verify(audit, never()).applied(any(), any(), any());
    }

    @Test
    void aMockedProviderIsMarkedMockOnTheRow() {
        gcp.mock = true;
        executor.prepare(request("GCP", "gs://pilot/finance/"));

        assertThat(executor.execute(APPROVAL, SERVICE).isMock()).isTrue();
    }

    @Test
    void aRepeatedExecuteNeverActsTwice() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        executor.execute(APPROVAL, SERVICE);
        gcp.calls.clear();

        ArchiveRuleEntity again = executor.execute(APPROVAL, SERVICE);

        assertThat(again.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(gcp.calls).isEmpty();
        verify(verifier).verifyById(APPROVAL, ASSET);
        verify(audit).requested(any(), any(), any());
    }

    @Test
    void anAuditFailureAfterTheEffectDoesNotUndoItOrThrow() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        doAnswer(inv -> {
            throw new AuditException("audit database down", null);
        }).when(audit).applied(any(), any(), any());

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(gcp.rules).containsExactly(row.getRuleId());
    }

    @Test
    void executingAnApprovalThatWasNeverPreparedIsAnError() {
        assertThatThrownBy(() -> executor.execute("unknown", SERVICE)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- direct change of one object --------------------------------------------------------------

    @Test
    void aSingleObjectIsChangedDirectlyAfterTheIntentRecord() {
        executor.prepare(request("AWS", "s3://pilot/finance/q1.parquet"));
        when(storage.archiveData(any(UUID.class), eq("s3://pilot/finance/q1.parquet"), eq("GLACIER_IR"), any()))
                .thenAnswer(inv -> {
                    aws.calls.add("storage.archiveData");
                    return ArchivalResult.builder().status(OperationStatus.COMPLETED).build();
                });
        aws.calls.clear();

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(row.getMode()).isEqualTo(ArchiveRuleEntity.MODE_DIRECT_OBJECT);
        assertThat(aws.calls).containsExactly("audit.requested", "storage.archiveData");
        assertThat(aws.calls).noneMatch(c -> c.startsWith("apply:"));
    }

    @Test
    void aFailedDirectChangeIsFailedNotApplied() {
        executor.prepare(request("AWS", "s3://pilot/finance/q1.parquet"));
        when(storage.archiveData(any(), anyString(), anyString(), any())).thenReturn(
                ArchivalResult.builder().status(OperationStatus.FAILED).errorMessage("AccessDenied").build());

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        assertThat(row.getLastError()).contains("AccessDenied");
    }

    @Test
    void aDirectChangeWithoutAStorageServiceFailsClosed() {
        ArchiveExecutor noStorage = new ArchiveExecutor(new RulePlanner(List.of(aws), new ArchiveRuleProperties(), rules),
                rules, verifier, audit, CLOCK, List.of(), config);
        noStorage.prepare(request("AWS", "s3://pilot/finance/q1.parquet"));

        assertThat(noStorage.execute(APPROVAL, SERVICE).getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
    }

    // ---- Q-609: one claim per row ------------------------------------------------------------------

    @Test
    void theRowIsClaimedBeforeTheIntentRecordAndTheWrite() {
        ArchiveRuleEntity planned = executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rules, audit);

        executor.execute(APPROVAL, SERVICE);

        order.verify(rules).claimPlanned(eq(planned.getRuleId()), eq(NOW));
        order.verify(audit).requested(any(), any(), any());
        assertThat(gcp.calls).containsExactly("inventory", "audit.requested", "apply:" + planned.getRuleId());
    }

    @Test
    void aWorkerThatLosesTheClaimWritesNoIntentRecordAndCallsNoProvider() {
        ArchiveRuleEntity planned = executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.calls.clear();
        // another worker claimed the row between this worker's read and its claim
        when(rules.claimPlanned(anyString(), any(Instant.class))).thenAnswer(inv -> {
            planned.markClaimed(NOW);
            return 0;
        });

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
        verify(audit, never()).requested(any(), any(), any());
        verify(audit, never()).applied(any(), any(), any());
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:") || c.equals("audit.requested"));
        assertThat(gcp.rules).isEmpty();
    }

    @Test
    void aRowAnotherWorkerIsAlreadyRunningIsNotRunAgain() {
        ArchiveRuleEntity planned = executor.prepare(request("GCP", "gs://pilot/finance/"));
        planned.markClaimed(NOW);
        gcp.calls.clear();

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
        verify(rules, never()).claimPlanned(anyString(), any(Instant.class));
        assertThat(gcp.calls).isEmpty();
    }

    /** Another worker applied the row while this one held an old copy: the stored row is replaced under it. */
    private ArchiveRuleEntity anotherWorkerAppliesTheRowWhileThisOneChecksTheApproval(RuntimeException failure) {
        ArchiveRuleEntity planned = executor.prepare(request("GCP", "gs://pilot/finance/"));
        ArchiveRuleEntity current = new ArchiveRuleEntity(planned.getRuleId(), APPROVAL, "fire-1", "task-1", ASSET,
                "gs://pilot/finance/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "COLDLINE", 0,
                NOW);
        current.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, NOW);
        when(verifier.verifyById(APPROVAL, ASSET)).thenAnswer(inv -> {
            store.put(current.getRuleId(), current);
            throw failure;
        });
        return current;
    }

    @Test
    void aRefusalAfterAnotherWorkerAppliedTheRowChangesNothingAndWritesNoRefusedRecord() {
        ArchiveRuleEntity current = anotherWorkerAppliesTheRowWhileThisOneChecksTheApproval(
                new ApprovalRefusedException(Reason.NOT_EXECUTABLE, "approval is EXPIRED and not executable"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row).isSameAs(current);
        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        verify(rules, never()).save(any());
        verify(audit, never()).refused(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTransientFailureAfterAnotherWorkerAppliedTheRowDoesNotRevertItToPlanned() {
        ArchiveRuleEntity current = anotherWorkerAppliesTheRowWhileThisOneChecksTheApproval(
                new ApprovalRefusedException(Reason.VERIFY_UNAVAILABLE, "the policy engine timed out"));

        assertThatThrownBy(() -> executor.execute(APPROVAL, SERVICE)).isInstanceOf(ApprovalRefusedException.class);

        assertThat(current.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(current.getAppliedAt()).isNotNull();
        verify(rules, never()).save(any());
    }

    // ---- Q-608: whether the provider already held the rule is stored on the row ---------------------

    @Test
    void anIdenticalRuleThatWasAlreadyThereIsRememberedOnTheRow() {
        ArchiveRuleEntity planned = executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.rules.add(planned.getRuleId()); // the customer's own identical rule
        gcp.calls.clear();

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(row.isAlreadyPresent()).isTrue();
        verify(audit).applied(eq(row), any(), org.mockito.ArgumentMatchers.argThat(m -> Boolean.TRUE.equals(m.get("alreadyPresent"))));
    }

    @Test
    void aRuleDlapWroteItselfIsNotMarkedAlreadyPresent() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.isAlreadyPresent()).isFalse();
    }

    // ---- Q-712: the claimer's own outcome writes are conditional on EXECUTING ------------------------

    private static boolean rowNotUpdated(Map<String, Object> details) {
        return Boolean.FALSE.equals(details.get("rowUpdated"));
    }

    @Test
    void q712_aSlowWorkersLateAppliedDoesNotOverwriteARowAnAdministratorResetToFailed() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        // while the worker writes the rule, an administrator reads the provider (rule absent) and resets the row
        gcp.duringApply = () -> only().markFailed(ArchiveRuleEntity.STATUS_FAILED,
                "RESET_RULE_ABSENT: reset by an administrator", NOW);

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).as("the reset keeps the row").isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        assertThat(row.getLastError()).startsWith("RESET_RULE_ABSENT");
        verify(rules).finishIfExecuting(eq(row.getRuleId()), eq(ArchiveRuleEntity.STATUS_APPLIED), anyString(),
                eq(false), eq(false), any(Instant.class));
        verify(rules, never()).save(any());
        // the rule WAS written at the provider, so the audit log still says so and flags that the row was not updated
        verify(audit).applied(eq(row), any(), org.mockito.ArgumentMatchers.argThat(ArchiveExecutorTest::rowNotUpdated));
    }

    @Test
    void q712_aSlowWorkersLateRenderedDoesNotOverwriteAResetRow() {
        executor.prepare(request("AZURE", "az://acct/raw/2019/"));
        azure.duringApply = () -> only().markFailed(ArchiveRuleEntity.STATUS_FAILED, "RESET_RULE_ABSENT: x", NOW);

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        assertThat(row.getRuleDocument()).as("the late document was not written").isNull();
        verify(rules).finishIfExecuting(eq(row.getRuleId()), eq(ArchiveRuleEntity.STATUS_RENDERED), anyString(),
                eq(false), eq(false), any(Instant.class));
        verify(rules, never()).save(any());
    }

    @Test
    void q712_aSlowWorkersLateFailureDoesNotOverwriteARowAnAdministratorResetToApplied() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        gcp.applyFailure = new RuleException(RuleException.Reason.PROVIDER_ERROR, "timeout");
        gcp.duringApply = () -> only().markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, true, NOW);

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).as("the reset found the rule and kept the row APPLIED")
                .isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(row.getLastError()).isNull();
        verify(rules).failIfExecuting(eq(row.getRuleId()), org.mockito.ArgumentMatchers.startsWith("PROVIDER_ERROR"),
                any(Instant.class));
        verify(rules, never()).save(any());
    }

    @Test
    void q712_aSlowDirectObjectWorkerDoesNotOverwriteAResetRow() {
        executor.prepare(request("AWS", "s3://pilot/finance/q1.parquet"));
        when(storage.archiveData(any(UUID.class), anyString(), anyString(), any())).thenAnswer(inv -> {
            only().markFailed(ArchiveRuleEntity.STATUS_FAILED, "RESET_RULE_ABSENT: x", NOW);
            return ArchivalResult.builder().status(OperationStatus.COMPLETED).build();
        });

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        verify(rules, never()).save(any());
    }

    @Test
    void q712_aWorkerThatStillOwnsTheRowRecordsItsOutcomeWithOneConditionalUpdate() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(row.getAppliedAt()).isEqualTo(NOW);
        verify(rules).finishIfExecuting(eq(row.getRuleId()), eq(ArchiveRuleEntity.STATUS_APPLIED), anyString(),
                eq(false), eq(false), eq(NOW));
        verify(audit).applied(eq(row), any(), org.mockito.ArgumentMatchers.argThat(m -> !m.containsKey("rowUpdated")));
    }

    @Test
    void q712_theAuditRefusalAfterTheClaimEndsTheRowFailedOnlyWhileItIsExecuting() {
        executor.prepare(request("GCP", "gs://pilot/finance/"));
        when(audit.requested(any(), any(), any())).thenAnswer(inv -> {
            only().markFailed(ArchiveRuleEntity.STATUS_FAILED, "RESET_RULE_ABSENT: x", NOW);
            throw new AuditException("down", null);
        });

        ArchiveRuleEntity row = executor.execute(APPROVAL, SERVICE);

        assertThat(row.getLastError()).startsWith("RESET_RULE_ABSENT");
        verify(rules).failIfExecuting(eq(row.getRuleId()), anyString(), any(Instant.class));
        verify(rules, never()).save(any());
    }

    // ---- Q-710: the loser of a same-instant race gets the same DUPLICATE_SCOPE the planner reports ------

    @Test
    void q710_aLostRaceOnTheLiveScopeIndexIsReportedAsDuplicateScopeNamingTheWinner() {
        ArchiveRuleEntity winner = new ArchiveRuleEntity("dlap-archive-winner000000", "appr-winner", "fire-w", "task-w",
                "asset-w", "gs://pilot/finance/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/",
                "COLDLINE", 0, NOW.minusSeconds(5));
        doThrow(new DataIntegrityViolationException("ux_archive_rules_live_gcs_scope")).when(rules).saveAndFlush(any());
        when(rules.findLiveScope(eq("GCP"), eq("pilot"), eq("finance/"), eq("COLDLINE"), eq(0), any(), anyString()))
                .thenReturn(List.of(winner));

        assertThatThrownBy(() -> executor.prepare(request("GCP", "gs://pilot/finance/")))
                .isInstanceOf(com.dlap.autoarchival.rule.DuplicateScopeException.class)
                .hasMessageContaining("dlap-archive-winner000000").hasMessageContaining("appr-winner")
                .satisfies(e -> {
                    var dup = (com.dlap.autoarchival.rule.DuplicateScopeException) e;
                    assertThat(dup.reason()).isEqualTo(RuleException.Reason.DUPLICATE_SCOPE);
                    assertThat(dup.existingRuleId()).isEqualTo("dlap-archive-winner000000");
                });
    }

    @Test
    void q710_aConstraintFailureThatIsNotTheLiveScopeIsRethrown() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("some other constraint");
        doThrow(other).when(rules).saveAndFlush(any());

        assertThatThrownBy(() -> executor.prepare(request("GCP", "gs://pilot/finance/"))).isSameAs(other);
    }

    @Test
    void q710_aLostRaceOnAnS3RuleIsNotReportedAsADuplicateScope() {
        // S3 rules carry the row's own id, so the live-scope index does not exist for them
        DataIntegrityViolationException other = new DataIntegrityViolationException("x");
        doThrow(other).when(rules).saveAndFlush(any());

        assertThatThrownBy(() -> executor.prepare(request("AWS", "s3://pilot/finance/"))).isSameAs(other);
        verify(rules, never()).findLiveScope(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), any(), anyString());
    }
}
