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

package com.dlap.autoarchival.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autoarchival.approval.ApprovalVerifier;
import com.dlap.autoarchival.approval.ArchiveApproval;
import com.dlap.autoarchival.approval.PolicyEngineApprovalClient;
import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.dto.ArchivalTaskResponse;
import com.dlap.autoarchival.executor.ArchiveExecutor;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.executor.ArchiveTaskSync;
import com.dlap.autoarchival.executor.InMemoryRules;
import com.dlap.autoarchival.mapper.ArchivalTaskMapper;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.repository.ArchivalTaskRepository;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.autoarchival.rule.FakeRuleProvider;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.service.AssetLocation;
import com.dlap.autoarchival.service.AssetLocationResolver;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.autoarchival.service.IArchivalTaskService;
import com.dlap.autoarchival.service.storage.ICloudStorageService;

/**
 * The policy path end to end through the real executor: where and how an archive runs comes from the
 * Kafka event, so an event that carries a real approval id but points somewhere the approval was not
 * given for must not reach a provider (docs/specs/approvals.md section 9, rule 3).
 */
class ArchivalTaskServiceForgedEventTest {

    private static final String APPROVAL_ID = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private final Map<String, ArchiveRuleEntity> store = new HashMap<>();
    private final FakeRuleProvider gcp = new FakeRuleProvider("GCP", true);
    private final PolicyEngineApprovalClient approvalClient = mock(PolicyEngineApprovalClient.class);
    private final AssetLocationResolver resolver = mock(AssetLocationResolver.class);
    private final ArchiveAudit audit = mock(ArchiveAudit.class);
    private final ArchiveTaskSync taskSync = mock(ArchiveTaskSync.class);
    private ArchivalTaskServiceImpl service;

    @BeforeEach
    void init() {
        ArchiveRuleRepository rules = InMemoryRules.over(store);
        RulePlanner planner = new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules);
        ApprovalVerifier verifier = new ApprovalVerifier(approvalClient);
        IArchivalConfigService config = mock(IArchivalConfigService.class);
        when(config.getArchivalConfig()).thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier("COLD").build());
        ArchiveExecutor executor = new ArchiveExecutor(planner, rules, verifier, audit, CLOCK, List.of(), config);
        ArchivalTaskRepository tasks = mock(ArchivalTaskRepository.class);
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArchivalTaskMapper mapper = mock(ArchivalTaskMapper.class);
        when(mapper.requestToEntity(any())).thenAnswer(inv -> new ArchivalTaskEntity());
        when(mapper.entityToTaskResponse(any())).thenAnswer(inv -> ArchivalTaskResponse.builder()
                .taskId(((ArchivalTaskEntity) inv.getArgument(0)).getTaskId()).build());
        service = new ArchivalTaskServiceImpl(tasks, mapper, config, List.of(mock(ICloudStorageService.class)),
                resolver, executor, taskSync, rules, verifier, approvalClient, audit, planner, CLOCK);
        ReflectionTestUtils.setField(service, "self", mock(IArchivalTaskService.class));
        // The policy engine says: approved for the finance/ prefix of the pilot bucket, tier COLD.
        when(approvalClient.fetchExecutable(APPROVAL_ID, ASSET)).thenReturn(new ArchiveApproval(APPROVAL_ID,
                "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false, "gs://pilot/", "GCP",
                Map.of("tier", "COLD", "prefix", "finance/")));
    }

    private static Map<String, Object> event(String snapshotUri, Map<String, Object> parameters) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", "e1");
        e.put("fireId", "fire-1");
        e.put("policyId", "archive-stale-pii");
        e.put("assetId", ASSET);
        e.put("actionType", "archive");
        e.put("actionParameters", parameters);
        e.put("matchSnapshot", Map.of("assetUri", snapshotUri, "cloudProvider", "GCP"));
        e.put("approval", new HashMap<>(Map.of("approvalId", APPROVAL_ID, "status", "APPROVED", "actionClass",
                "ARCHIVE", "requiredApprovals", 1, "approvers", List.of("alice-sub"), "breakGlass", false)));
        return e;
    }

    private void snapshotResolvesTo(String uri) {
        when(resolver.resolveForPolicyAction(eq(ASSET), any())).thenReturn(Optional.of(new AssetLocation("GCP", uri)));
    }

    @Test
    void anEventWhoseSnapshotPointsAtAnotherBucketNeverReachesTheProvider() {
        snapshotResolvesTo("gs://other-bucket/");

        service.processArchivalAction(event("gs://other-bucket/", Map.of("tier", "COLD", "prefix", "finance/")));

        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
        assertThat(gcp.rules).isEmpty();
        assertThat(store.values()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
            assertThat(r.getLastError()).startsWith("ASSET_MISMATCH");
        });
        verify(audit).refused(eq(APPROVAL_ID), anyString(), eq("ASSET_MISMATCH"), anyString(), eq(APPROVAL_ID),
                anyString());
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void anEventThatWidensTheApprovedPrefixToTheWholeBucketNeverReachesTheProvider() {
        snapshotResolvesTo("gs://pilot/");

        service.processArchivalAction(event("gs://pilot/", Map.of("tier", "COLD", "prefix", "")));

        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
        assertThat(store.values()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED));
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void anEventThatAddsTheArchiveTierToAnApprovalSilentOnTheTierNeverReachesTheProvider() {
        // the policy engine holds an approval for gs://pilot/finance/ with no tier (so the default, COLD)
        when(approvalClient.fetchExecutable(APPROVAL_ID, ASSET)).thenReturn(new ArchiveApproval(APPROVAL_ID,
                "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false, "gs://pilot/", "GCP",
                Map.of("prefix", "finance/")));
        snapshotResolvesTo("gs://pilot/");

        service.processArchivalAction(event("gs://pilot/", Map.of("targetStorageClass", "ARCHIVE", "prefix", "finance/")));

        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
        assertThat(gcp.rules).isEmpty();
        assertThat(store.values()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
            assertThat(r.getLastError()).startsWith("ASSET_MISMATCH");
        });
        verify(audit).refused(eq(APPROVAL_ID), anyString(), eq("ASSET_MISMATCH"), anyString(), eq(APPROVAL_ID),
                anyString());
        verify(audit, never()).requested(any(), any(), any());
    }

    @Test
    void theGenuineEventForTheApprovedPlaceStillRuns() {
        snapshotResolvesTo("gs://pilot/");

        service.processArchivalAction(event("gs://pilot/", Map.of("tier", "COLD", "prefix", "finance/")));

        assertThat(gcp.rules).hasSize(1);
        assertThat(store.values()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED));
        verify(audit).requested(any(), any(), any());
    }
}
