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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.kafka.support.Acknowledgment;
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
import com.dlap.autoarchival.service.impl.ArchivalTaskServiceImpl;
import com.dlap.autoarchival.service.storage.ICloudStorageService;

/**
 * IDEM-01 (Q-856): {@link PolicyActionEventListener} archives a repeated {@code fireId} once. Delivery on
 * {@code dlap.policies.actions} is at least once and the policy engine republishes a fire with the same
 * {@code fireId} when its source event is redelivered. The listener, {@link ArchivalTaskServiceImpl}, the
 * {@link ArchiveExecutor} and the {@link RulePlanner} are the service's own over an in-memory rule store that
 * keeps the database's lookups and conditional updates; the provider is {@link FakeRuleProvider}, which records
 * every call. The row-level guarantee, the unique index {@code ux_archive_rules_fire}, is pinned against
 * PostgreSQL by {@code ArchiveFlowContainerTest}. A copy of the fire with a different approval id is the same
 * decision, not a second archive.
 */
class PolicyActionEventListenerDuplicateTest {

    private static final String APPROVAL = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final String FIRE = "fire-idem-01";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private final Map<String, ArchiveRuleEntity> store = new HashMap<>();
    private final ArchiveRuleRepository rules = InMemoryRules.over(store);
    private final FakeRuleProvider gcp = new FakeRuleProvider("GCP", true);
    private final ApprovalVerifier verifier = mock(ApprovalVerifier.class);
    private final ArchiveAudit audit = mock(ArchiveAudit.class);
    private final ArchiveTaskSync sync = mock(ArchiveTaskSync.class);
    private final ArchivalTaskRepository tasks = mock(ArchivalTaskRepository.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private PolicyActionEventListener listener;

    @BeforeEach
    void init() {
        IArchivalConfigService config = mock(IArchivalConfigService.class);
        when(config.getArchivalConfig()).thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier("COLD").build());
        ICloudStorageService storage = mock(ICloudStorageService.class);
        when(storage.supportsCloudProvider(anyString())).thenReturn(true);
        RulePlanner planner = new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules);
        ArchiveExecutor executor = new ArchiveExecutor(planner, rules, verifier, audit, CLOCK, List.of(storage), config);

        AssetLocationResolver resolver = mock(AssetLocationResolver.class);
        when(resolver.resolveForPolicyAction(anyString(), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://pilot/finance/")));
        ArchivalTaskMapper mapper = mock(ArchivalTaskMapper.class);
        when(mapper.requestToEntity(any())).thenAnswer(inv -> new ArchivalTaskEntity());
        when(mapper.entityToTaskResponse(any())).thenAnswer(inv -> {
            ArchivalTaskEntity e = inv.getArgument(0);
            return ArchivalTaskResponse.builder().taskId(e.getTaskId()).status(String.valueOf(e.getStatus())).build();
        });
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // The policy engine's answer describes exactly what the event claims: the place and the tier.
        ArchiveApproval approved = new ArchiveApproval(APPROVAL, "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false,
                "gs://pilot/finance/", "GCP", Map.of("tier", "COLD"));
        when(verifier.fromEvent(any())).thenReturn(approved);
        when(verifier.verifyById(anyString(), anyString())).thenReturn(approved);

        ArchivalTaskServiceImpl service = new ArchivalTaskServiceImpl(tasks, mapper, config, List.of(storage), resolver,
                executor, sync, rules, verifier, mock(PolicyEngineApprovalClient.class), audit, planner, CLOCK);
        ReflectionTestUtils.setField(service, "self", mock(IArchivalTaskService.class));
        listener = new PolicyActionEventListener(service);
    }

    private static Map<String, Object> archive(String fireId) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", "e1");
        e.put("fireId", fireId);
        e.put("policyId", "archive-stale-pii");
        e.put("assetId", ASSET);
        e.put("actionType", "archive");
        e.put("actionParameters", new HashMap<>(Map.of("tier", "COLD")));
        e.put("approval", new HashMap<>(Map.of("approvalId", APPROVAL, "status", "APPROVED", "actionClass", "ARCHIVE",
                "requiredApprovals", 1, "approvers", List.of("alice-sub"), "breakGlass", false)));
        return e;
    }

    private void deliver(Map<String, Object> event) {
        listener.handlePolicyActionEvent(event, "dlap.policies.actions", 0, 7L, ack);
    }

    @Test
    void aRepeatedFireIdArchivesOnceAndCreatesOneRuleOneTaskAndOneIntentRecord() {
        deliver(archive(FIRE));
        assertThat(store).hasSize(1);
        ArchiveRuleEntity row = store.values().iterator().next();
        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(row.getFireId()).isEqualTo(FIRE);
        List<String> afterFirst = List.copyOf(gcp.calls);
        assertThat(gcp.rules).hasSize(1);

        deliver(archive(FIRE));
        deliver(archive(FIRE));

        assertThat(store).as("rules").hasSize(1);
        assertThat(gcp.rules).as("native rules at the provider").hasSize(1);
        assertThat(gcp.calls).as("no provider call by the copies").isEqualTo(afterFirst);
        verify(tasks, times(1)).save(any());
        verify(audit, times(1)).requested(any(), any(), any());
        verify(sync, times(1)).sync(anyString());
        verify(ack, times(3)).acknowledge();
    }

    @Test
    void aCopyOfTheFireUnderAnotherApprovalIdIsTheSameDecision() {
        deliver(archive(FIRE));
        List<String> afterFirst = List.copyOf(gcp.calls);
        String other = UUID.randomUUID().toString();
        Map<String, Object> copy = archive(FIRE);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) copy.get("approval");
        block.put("approvalId", other);
        when(verifier.fromEvent(copy)).thenReturn(new ArchiveApproval(other, "APPROVED", "ARCHIVE", 1,
                List.of("alice-sub"), false, "gs://pilot/finance/", "GCP", Map.of("tier", "COLD")));

        deliver(copy);

        assertThat(store).hasSize(1);
        assertThat(gcp.calls).isEqualTo(afterFirst);
        verify(tasks, times(1)).save(any());
    }

    @Test
    void aDifferentFireOnTheSameScopeIsNotAnotherArchive() {
        deliver(archive(FIRE));
        List<String> afterFirst = List.copyOf(gcp.calls);

        // a later fire of the same policy brings its own approval for the same place: the scope is already held
        String laterApproval = UUID.randomUUID().toString();
        Map<String, Object> later = archive("fire-idem-02");
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) later.get("approval");
        block.put("approvalId", laterApproval);
        when(verifier.fromEvent(later)).thenReturn(new ArchiveApproval(laterApproval, "APPROVED", "ARCHIVE", 1,
                List.of("alice-sub"), false, "gs://pilot/finance/", "GCP", Map.of("tier", "COLD")));
        when(verifier.verifyById(laterApproval, ASSET)).thenReturn(new ArchiveApproval(laterApproval, "APPROVED",
                "ARCHIVE", 1, List.of("alice-sub"), false, "gs://pilot/finance/", "GCP", Map.of("tier", "COLD")));

        deliver(later);

        assertThat(gcp.rules).as("the provider still holds one rule").hasSize(1);
        assertThat(gcp.calls.stream().filter(c -> c.startsWith("apply:")).count()).isEqualTo(1);
        assertThat(afterFirst).isNotEmpty();
    }
}
