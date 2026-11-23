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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autoarchival.approval.ApprovalRefusedException;
import com.dlap.autoarchival.approval.ApprovalVerifier;
import com.dlap.autoarchival.approval.PolicyEngineApprovalClient;
import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.dto.ArchivalTaskRequest;
import com.dlap.autoarchival.dto.ArchivalTaskResponse;
import com.dlap.autoarchival.dto.ArchivalTaskStatusDTO;
import com.dlap.autoarchival.dto.RestoreRequestDTO;
import com.dlap.autoarchival.exception.PermanentArchivalException;
import com.dlap.autoarchival.exception.TransientArchivalException;
import com.dlap.autoarchival.executor.ArchiveExecutor;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.executor.ArchiveTaskSync;
import com.dlap.autoarchival.mapper.ArchivalTaskMapper;
import com.dlap.autoarchival.model.ArchivalStatus;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.repository.ArchivalTaskRepository;
import com.dlap.autoarchival.rule.DuplicateScopeException;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.service.AssetLocation;
import com.dlap.autoarchival.service.AssetLocationResolver;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.autoarchival.service.IArchivalTaskService;
import com.dlap.autoarchival.service.storage.ICloudStorageService;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchivalTaskServiceImplTest {

    private static final String APPROVAL_ID = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ArchivalTaskRepository taskRepository;
    @Mock
    private ArchivalTaskMapper taskMapper;
    @Mock
    private IArchivalConfigService archivalConfigService;
    @Mock
    private ICloudStorageService cloudStorageService;
    @Mock
    private AssetLocationResolver assetLocationResolver;
    @Mock
    private ArchiveExecutor executor;
    @Mock
    private ArchiveTaskSync taskSync;
    @Mock
    private ArchiveRuleRepository ruleRepository;
    @Mock
    private PolicyEngineApprovalClient approvalClient;
    @Mock
    private ArchiveAudit audit;
    @Mock
    private RulePlanner planner;
    @Mock
    private IArchivalTaskService selfProxy;

    private ArchivalTaskServiceImpl service;

    @BeforeEach
    void init() {
        service = new ArchivalTaskServiceImpl(taskRepository, taskMapper, archivalConfigService,
                List.of(cloudStorageService), assetLocationResolver, executor, taskSync, ruleRepository,
                new ApprovalVerifier(approvalClient), approvalClient, audit, planner, CLOCK);
        ReflectionTestUtils.setField(service, "self", selfProxy);
        when(archivalConfigService.getArchivalConfig())
                .thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier("COLD").build());
        when(ruleRepository.findByApprovalId(anyString())).thenReturn(Optional.empty());
        when(ruleRepository.findByFireId(anyString())).thenReturn(Optional.empty());
        // the database's conditional removal: 1 while the row has the status the restore loaded
        when(ruleRepository.markRemovedIf(anyString(), anyString(), any())).thenReturn(1);
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskMapper.requestToEntity(any())).thenAnswer(inv -> new ArchivalTaskEntity());
        when(taskMapper.entityToTaskResponse(any())).thenAnswer(inv -> {
            ArchivalTaskEntity e = inv.getArgument(0);
            return ArchivalTaskResponse.builder().taskId(e.getTaskId()).status(String.valueOf(e.getStatus())).build();
        });
    }

    private static Map<String, Object> approvedEvent(String assetId) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", "e1");
        event.put("fireId", "fire-1");
        event.put("policyId", "archive-stale-pii");
        event.put("assetId", assetId);
        event.put("actionType", "archive");
        event.put("approval", new HashMap<>(Map.of("approvalId", APPROVAL_ID, "status", "APPROVED",
                "actionClass", "ARCHIVE", "requiredApprovals", 1, "approvers", List.of("alice-sub"),
                "breakGlass", false)));
        return event;
    }

    private ArchiveRuleEntity row(String taskId, String status) {
        ArchiveRuleEntity r = new ArchiveRuleEntity("dlap-archive-aaaaaaaaaaaa", APPROVAL_ID, "fire-1", taskId,
                "asset-1", "gs://b/p/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "b", "p/", "COLDLINE", 0,
                Instant.parse("2026-11-01T09:00:00Z"));
        if (!ArchiveRuleEntity.STATUS_PLANNED.equals(status)) {
            r.markFailed(status, "x", Instant.parse("2026-11-01T09:30:00Z"));
        }
        return r;
    }

    /** The database's conditional update, on the given in-memory rows: changes a row only while it is PLANNED. */
    private void conditionalEndOn(ArchiveRuleEntity... rows) {
        when(ruleRepository.endIfPlanned(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            for (ArchiveRuleEntity r : rows) {
                if (r.getRuleId().equals(inv.getArgument(0))
                        && ArchiveRuleEntity.STATUS_PLANNED.equals(r.getStatus())) {
                    r.markFailed(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
                    return 1;
                }
            }
            return 0;
        });
    }

    @Test
    void submitArchivalTask_withoutAssetIds_returnsValidationFailed() {
        ArchivalTaskRequest req = ArchivalTaskRequest.builder()
                .taskName("t")
                .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(Collections.emptyList()).build())
                .build();

        var resp = service.submitArchivalTask(req);
        assertThat(resp.getStatus()).isEqualTo("FAILED_VALIDATION");
        assertThat(resp.getTaskId()).isNull();
    }

    @Test
    void processArchivalAction_ioWrappedRuntime_isTransient() {
        when(taskRepository.save(any())).thenThrow(new RuntimeException(new IOException("timeout")));
        when(executor.prepare(any())).thenReturn(row("t", ArchiveRuleEntity.STATUS_PLANNED));
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));

        assertThatThrownBy(() -> service.processArchivalAction(approvedEvent(assetId)))
                .isInstanceOf(TransientArchivalException.class);
    }

    @Test
    void processArchivalAction_plainRuntime_isPermanent() {
        when(taskRepository.save(any())).thenThrow(new RuntimeException("boom"));
        when(executor.prepare(any())).thenReturn(row("t", ArchiveRuleEntity.STATUS_PLANNED));
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));

        assertThatThrownBy(() -> service.processArchivalAction(approvedEvent(assetId)))
                .isInstanceOf(PermanentArchivalException.class);
    }

    @Test
    void processArchivalAction_missingAssetId_isPermanent() {
        Map<String, Object> event = approvedEvent("x");
        event.remove("assetId");

        assertThatThrownBy(() -> service.processArchivalAction(event)).isInstanceOf(PermanentArchivalException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void resolveCloudStorageService_noServiceForProvider_failsClosed() {
        // Only one storage service is registered and it supports nothing by default. A GCP
        // task must fail closed (null) - never fall back to the first (AWS) service and
        // silently act on a gs:// asset in a different cloud (choke-point #4).
        ReflectionTestUtils.setField(service, "defaultCloudProvider", "GCP");
        ArchivalTaskEntity task = new ArchivalTaskEntity();

        Object resolved = ReflectionTestUtils.invokeMethod(service, "resolveCloudStorageService", task);

        assertThat(resolved).isNull();
    }

    @Test
    void resolveCloudStorageService_serviceSupportsProvider_returnsIt() {
        when(cloudStorageService.supportsCloudProvider("AWS")).thenReturn(true);
        ReflectionTestUtils.setField(service, "defaultCloudProvider", "AWS");
        ArchivalTaskEntity task = new ArchivalTaskEntity();

        Object resolved = ReflectionTestUtils.invokeMethod(service, "resolveCloudStorageService", task);

        assertThat(resolved).isSameAs(cloudStorageService);
    }

    // ------------------------------------------------------------------
    // Approvals.md section 9: no executable approval, no archive.
    // ------------------------------------------------------------------

    @Test
    void processArchivalAction_withoutApproval_isRefusedAuditedAndNeverPlanned() {
        Map<String, Object> event = approvedEvent(UUID.randomUUID().toString());
        event.remove("approval");

        service.processArchivalAction(event);

        verify(audit).refused(eq("fire-1"), any(), eq("NO_APPROVAL"), anyString(), isNull(), isNull());
        verifyNoInteractions(executor);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void processArchivalAction_withRequireApprovalFlagAndNoApprovalBlock_isStillRefused() {
        // requireApproval:true without an approval is the case section 9 rule 1 names.
        Map<String, Object> event = approvedEvent(UUID.randomUUID().toString());
        event.remove("approval");
        event.put("requireApproval", true);

        service.processArchivalAction(event);

        verifyNoInteractions(executor);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void processArchivalAction_pendingApprovalBlock_isRefused() {
        Map<String, Object> event = approvedEvent(UUID.randomUUID().toString());
        ((Map<String, Object>) event.get("approval")).put("status", "PENDING");

        service.processArchivalAction(event);

        verify(audit).refused(anyString(), any(), eq("NOT_APPROVED"), anyString(), isNull(), isNull());
        verifyNoInteractions(executor);
    }

    @Test
    void processArchivalAction_approvalForAnotherActionClass_isRefused() {
        Map<String, Object> event = approvedEvent(UUID.randomUUID().toString());
        ((Map<String, Object>) event.get("approval")).put("actionClass", "HARD_DELETE");

        service.processArchivalAction(event);

        verify(audit).refused(anyString(), any(), eq("WRONG_CLASS"), anyString(), isNull(), isNull());
        verifyNoInteractions(executor);
    }

    @Test
    void processArchivalAction_auditFailureOnRefusal_doesNotThrow() {
        Map<String, Object> event = approvedEvent(UUID.randomUUID().toString());
        event.remove("approval");
        when(audit.refused(anyString(), any(), anyString(), anyString(), any(), any()))
                .thenThrow(new AuditException("down", null));

        service.processArchivalAction(event);

        verifyNoInteractions(executor);
    }

    @Test
    void processArchivalAction_validApproval_putsRealUriProviderAndApprovalOnTheTaskAndRunsIt() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://prod-bucket/warehouse/pii/")));
        ArchiveRuleEntity planned = row("t", ArchiveRuleEntity.STATUS_PLANNED);
        when(executor.prepare(any())).thenReturn(planned);

        service.processArchivalAction(approvedEvent(assetId));

        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        ArchivalTaskEntity task = saved.getValue();
        assertThat(task.getAssetStatuses()).hasSize(1);
        ArchivalTaskEntity.AssetArchivalStatusData status = task.getAssetStatuses().get(0);
        assertThat(status.getArchivalLocation()).isEqualTo("gs://prod-bucket/warehouse/pii/");
        assertThat(status.getCloudProvider()).isEqualTo("GCP");
        assertThat(status.getStatus()).isEqualTo("PENDING");
        assertThat(status.getApprovalId()).isEqualTo(APPROVAL_ID);
        assertThat(status.getRuleId()).isEqualTo("dlap-archive-aaaaaaaaaaaa");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.SUBMITTED);
        // Never an invented location.
        assertThat(status.getArchivalLocation()).doesNotContain("unknown");

        ArgumentCaptor<ArchiveExecutor.Request> req = ArgumentCaptor.forClass(ArchiveExecutor.Request.class);
        verify(executor).prepare(req.capture());
        assertThat(req.getValue().approvalId()).isEqualTo(APPROVAL_ID);
        assertThat(req.getValue().fireId()).isEqualTo("fire-1");
        assertThat(req.getValue().assetUri()).isEqualTo("gs://prod-bucket/warehouse/pii/");
        verify(executor).execute(eq(APPROVAL_ID), any(AuditActor.class));
        verify(taskSync).sync(task.getTaskId());
    }

    @Test
    void processArchivalAction_repeatedApproval_isTheSameDecisionAndMakesNoSecondTask() {
        when(ruleRepository.findByApprovalId(APPROVAL_ID))
                .thenReturn(Optional.of(row("t0", ArchiveRuleEntity.STATUS_APPLIED)));

        service.processArchivalAction(approvedEvent(UUID.randomUUID().toString()));

        verify(taskRepository, never()).save(any());
        verify(executor, never()).prepare(any());
        verify(executor, never()).execute(anyString(), any());
    }

    @Test
    void processArchivalAction_repeatedFireWithAnotherApprovalId_isTheSameDecision() {
        when(ruleRepository.findByFireId("fire-1")).thenReturn(Optional.of(row("t0", ArchiveRuleEntity.STATUS_EXECUTED)));

        service.processArchivalAction(approvedEvent(UUID.randomUUID().toString()));

        verify(taskRepository, never()).save(any());
        verify(executor, never()).execute(anyString(), any());
    }

    @Test
    void processArchivalAction_repeatedApprovalStillPlanned_isResumedWithoutANewTask() {
        when(ruleRepository.findByApprovalId(APPROVAL_ID))
                .thenReturn(Optional.of(row("t0", ArchiveRuleEntity.STATUS_PLANNED)));

        service.processArchivalAction(approvedEvent(UUID.randomUUID().toString()));

        verify(taskRepository, never()).save(any());
        verify(executor).execute(eq(APPROVAL_ID), any(AuditActor.class));
        verify(taskSync).sync("t0");
    }

    @Test
    void processArchivalAction_approvalCannotBeChecked_isTransientSoKafkaRetries() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        when(executor.prepare(any())).thenReturn(row("t", ArchiveRuleEntity.STATUS_PLANNED));
        when(executor.execute(anyString(), any())).thenThrow(new ApprovalRefusedException(
                ApprovalRefusedException.Reason.VERIFY_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> service.processArchivalAction(approvedEvent(assetId)))
                .isInstanceOf(TransientArchivalException.class);
    }

    @Test
    void processArchivalAction_planRefused_failsTheAssetAndAuditsTheRefusal() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        when(executor.prepare(any())).thenThrow(new RuleException(RuleException.Reason.INVALID_TIER, "no such tier"));

        service.processArchivalAction(approvedEvent(assetId));

        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ArchivalStatus.REFUSED);
        assertThat(saved.getValue().getAssetStatuses().get(0).getErrorMessage()).contains("INVALID_TIER");
        verify(audit).refused(anyString(), any(), eq("INVALID_TIER"), anyString(), eq(APPROVAL_ID), anyString());
        verify(executor, never()).execute(anyString(), any());
    }

    @Test
    void processArchivalAction_unresolvableAsset_failsClosedWithoutAnyCloudCall() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any())).thenReturn(Optional.empty());

        service.processArchivalAction(approvedEvent(assetId));

        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        ArchivalTaskEntity task = saved.getValue();
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.FAILED);
        assertThat(task.getAssetStatuses().get(0).getStatus()).isEqualTo("FAILED");
        assertThat(task.getAssetStatuses().get(0).getArchivalLocation()).isNull();
        assertThat(task.getAssetStatuses().get(0).getErrorMessage()).contains("Unresolved asset location");
        // Fail closed: nothing is prepared, executed or sent to a cloud.
        verify(executor, never()).prepare(any());
        verify(executor, never()).execute(anyString(), any());
        verifyNoInteractions(cloudStorageService);
    }

    // ------------------------------------------------------------------
    // Operator-submitted tasks ask for an approval and wait for it (section 9, rule 4).
    // ------------------------------------------------------------------

    @Test
    void submitArchivalTask_asksAnApprovalOnBehalfOfTheCallerAndDoesNotRun() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("AWS", "s3://pilot-data/finance/")));
        when(approvalClient.ask(eq(assetId), eq("s3://pilot-data/finance/"), eq("AWS"), eq("archive"), any(), anyString(),
                eq("caller-sub"), anyString())).thenReturn(APPROVAL_ID);
        when(executor.prepare(any())).thenReturn(row("t", ArchiveRuleEntity.STATUS_PLANNED));
        ArchivalTaskRequest req = ArchivalTaskRequest.builder().taskName("t")
                .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(List.of(assetId)).build())
                .overrideConfig(ArchivalTaskRequest.ArchivalConfigOverride.builder().targetArchivalTier("ARCHIVE")
                        .prefix("2019/").afterDays(90).build())
                .build();

        service.submitArchivalTask(req, "caller-sub");

        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ArchivalStatus.PENDING_APPROVAL);
        assertThat(saved.getValue().getTriggeredBy()).isEqualTo("caller-sub");
        assertThat(saved.getValue().getAssetStatuses().get(0).getApprovalId()).isEqualTo(APPROVAL_ID);
        ArgumentCaptor<Map<String, Object>> asked = ArgumentCaptor.forClass(Map.class);
        verify(approvalClient).ask(anyString(), anyString(), anyString(), anyString(), asked.capture(), anyString(),
                anyString(), anyString());
        assertThat(asked.getValue()).containsEntry("tier", "ARCHIVE").containsEntry("prefix", "2019/")
                .containsEntry("afterDays", 90);
        // The plan is validated before anybody is asked to approve it.
        verify(planner).plan(eq("AWS"), eq("s3://pilot-data/finance/"), any(), any(), isNull(), eq(0));
        verify(executor, never()).execute(anyString(), any());
        verify(selfProxy, never()).processArchivalTaskAsync(anyString());
    }

    @Test
    void submitArchivalTask_invalidTierIsRefusedBeforeAnyoneIsAskedToApprove() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("AWS", "s3://b/")));
        when(planner.plan(anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuleException(RuleException.Reason.INVALID_TIER, "STANDARD_ARCHIVE is not a tier"));
        ArchivalTaskRequest req = ArchivalTaskRequest.builder().taskName("t")
                .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(List.of(assetId)).build()).build();

        service.submitArchivalTask(req, "caller-sub");

        verify(approvalClient, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getAssetStatuses().get(0).getStatus()).isEqualTo("REFUSED");
    }

    @Test
    void submitArchivalTask_approvalRequestFails_failsTheAssetClosed() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("AWS", "s3://b/")));
        when(approvalClient.ask(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("the policy engine refused the approval request: HTTP 403"));
        ArchivalTaskRequest req = ArchivalTaskRequest.builder().taskName("t")
                .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(List.of(assetId)).build()).build();

        service.submitArchivalTask(req, "caller-sub");

        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ArchivalStatus.FAILED);
        verify(executor, never()).prepare(any());
    }

    @Test
    void approveTask_noLongerStartsAnything() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.PENDING_APPROVAL);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        when(taskMapper.entityToStatusDTO(task)).thenReturn(ArchivalTaskStatusDTO.builder().taskId("t1")
                .status("PENDING_APPROVAL").build());

        ArchivalTaskStatusDTO out = service.approveTask("t1", null);

        assertThat(out.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(out.getErrorMessages()).singleElement().asString().contains("approvals API");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.PENDING_APPROVAL);
        verify(selfProxy, never()).processArchivalTaskAsync(anyString());
        verifyNoInteractions(executor);
    }

    @Test
    void rejectTask_marksWaitingRowsRefusedSoALateApprovalCannotRunThem() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.PENDING_APPROVAL);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        ArchiveRuleEntity planned = row("t1", ArchiveRuleEntity.STATUS_PLANNED);
        when(ruleRepository.findByTaskId("t1")).thenReturn(List.of(planned));
        conditionalEndOn(planned);
        when(taskMapper.entityToStatusDTO(any())).thenReturn(ArchivalTaskStatusDTO.builder().status("REJECTED").build());

        service.rejectTask("t1", null, "ops-sub");

        assertThat(planned.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.REJECTED);
    }

    @Test
    void rejectTask_writesTheRefusalToTheAuditChainOncePerWaitingRowNamingTheOperator() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.PENDING_APPROVAL);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        ArchiveRuleEntity waiting = row("t1", ArchiveRuleEntity.STATUS_PLANNED);
        ArchiveRuleEntity applied = row("t1", ArchiveRuleEntity.STATUS_APPLIED);
        when(ruleRepository.findByTaskId("t1")).thenReturn(List.of(waiting, applied));
        conditionalEndOn(waiting, applied);
        when(taskMapper.entityToStatusDTO(any())).thenReturn(ArchivalTaskStatusDTO.builder().status("REJECTED").build());

        service.rejectTask("t1", null, "ops-sub");

        ArgumentCaptor<AuditActor> actor = ArgumentCaptor.forClass(AuditActor.class);
        verify(audit, org.mockito.Mockito.times(1)).refused(eq(APPROVAL_ID + ":task-rejected"), eq("gs://b/p/"),
                eq("TASK_REJECTED"), anyString(), eq(APPROVAL_ID), eq("t1"), actor.capture());
        assertThat(actor.getValue()).isEqualTo(AuditActor.user("ops-sub"));
        assertThat(applied.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    @Test
    void rejectTask_aTaskNotWaitingWritesNothingToTheChain() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.EXECUTED);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        when(taskMapper.entityToStatusDTO(any())).thenReturn(ArchivalTaskStatusDTO.builder().status("EXECUTED").build());

        service.rejectTask("t1", null, "ops-sub");

        verifyNoInteractions(audit);
    }

    @Test
    void rejectTask_anAuditFailureDoesNotUndoTheRejection() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.PENDING_APPROVAL);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        ArchiveRuleEntity waiting = row("t1", ArchiveRuleEntity.STATUS_PLANNED);
        when(ruleRepository.findByTaskId("t1")).thenReturn(List.of(waiting));
        conditionalEndOn(waiting);
        when(audit.refused(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new AuditException("audit database down", null));
        when(taskMapper.entityToStatusDTO(any())).thenReturn(ArchivalTaskStatusDTO.builder().status("REJECTED").build());

        service.rejectTask("t1", null, null);

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.REJECTED);
    }

    // ------------------------------------------------------------------
    // The DECIDED event is only a prompt.
    // ------------------------------------------------------------------

    @Test
    void onApprovalDecided_executable_startsTheStoredArchive() {
        when(ruleRepository.findByApprovalId(APPROVAL_ID))
                .thenReturn(Optional.of(row("t1", ArchiveRuleEntity.STATUS_PLANNED)));

        service.onApprovalDecided(APPROVAL_ID, true, "APPROVED");

        verify(selfProxy).executeApprovalAsync(APPROVAL_ID);
    }

    @Test
    void onApprovalDecided_rejected_endsTheArchiveAndAuditsIt() {
        ArchiveRuleEntity planned = row("t1", ArchiveRuleEntity.STATUS_PLANNED);
        when(ruleRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(planned));
        conditionalEndOn(planned);

        service.onApprovalDecided(APPROVAL_ID, false, "REJECTED");

        assertThat(planned.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);
        assertThat(planned.getLastError()).contains("APPROVAL_REJECTED");
        verify(audit).refused(anyString(), anyString(), eq("APPROVAL_REJECTED"), anyString(), eq(APPROVAL_ID), eq("t1"));
        verify(taskSync).sync("t1");
        verify(selfProxy, never()).executeApprovalAsync(anyString());
    }

    @Test
    void onApprovalDecided_rejectedAfterAnotherWorkerClaimedTheRow_writesNothingAndAuditsNothing() {
        ArchiveRuleEntity staleCopy = row("t1", ArchiveRuleEntity.STATUS_PLANNED);
        ArchiveRuleEntity current = row("t1", ArchiveRuleEntity.STATUS_APPLIED);
        when(ruleRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(staleCopy));
        conditionalEndOn(current); // the database row is APPLIED: the conditional update changes nothing

        service.onApprovalDecided(APPROVAL_ID, false, "EXPIRED");

        assertThat(staleCopy.getStatus()).as("the old copy is never saved").isEqualTo(ArchiveRuleEntity.STATUS_PLANNED);
        verify(ruleRepository, never()).save(any());
        verifyNoInteractions(audit, taskSync);
    }

    @Test
    void rejectTask_aRowAnotherWorkerClaimedFirstIsNotRefusedOrAudited() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.PENDING_APPROVAL);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        ArchiveRuleEntity staleCopy = row("t1", ArchiveRuleEntity.STATUS_PLANNED);
        ArchiveRuleEntity current = row("t1", ArchiveRuleEntity.STATUS_EXECUTING);
        when(ruleRepository.findByTaskId("t1")).thenReturn(List.of(staleCopy));
        conditionalEndOn(current);
        when(taskMapper.entityToStatusDTO(any())).thenReturn(ArchivalTaskStatusDTO.builder().status("REJECTED").build());

        service.rejectTask("t1", null, "ops-sub");

        verify(ruleRepository, never()).save(any());
        verifyNoInteractions(audit);
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.REJECTED);
    }

    @Test
    void onApprovalDecided_forAnApprovalThisServiceNeverStored_isIgnored() {
        service.onApprovalDecided("someone-elses-approval", true, "APPROVED");

        verifyNoInteractions(selfProxy, audit, taskSync);
    }

    @Test
    void onApprovalDecided_forAnArchiveAlreadyDecided_isIgnored() {
        when(ruleRepository.findByApprovalId(APPROVAL_ID))
                .thenReturn(Optional.of(row("t1", ArchiveRuleEntity.STATUS_APPLIED)));

        service.onApprovalDecided(APPROVAL_ID, true, "APPROVED");

        verify(selfProxy, never()).executeApprovalAsync(anyString());
    }

    @Test
    void executeApprovalAsync_runsTheStoredArchiveAsTheRequesterAndDerivesTheTask() {
        ArchiveRuleEntity planned = row("t1", ArchiveRuleEntity.STATUS_PLANNED);
        when(ruleRepository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(planned));
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTriggeredBy("caller-sub");
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));

        service.executeApprovalAsync(APPROVAL_ID);

        ArgumentCaptor<AuditActor> actor = ArgumentCaptor.forClass(AuditActor.class);
        verify(executor).execute(eq(APPROVAL_ID), actor.capture());
        assertThat(actor.getValue()).isEqualTo(AuditActor.user("caller-sub"));
        verify(taskSync).sync("t1");
    }

    // ------------------------------------------------------------------
    // Task processing and restore.
    // ------------------------------------------------------------------

    @Test
    void processArchivalTaskAsync_runsOnlyPlannedRowsAndSkipsTheRest() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("task-1");
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        ArchiveRuleEntity planned = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        ArchiveRuleEntity done = new ArchiveRuleEntity("dlap-archive-bbbbbbbbbbbb", "approval-2", null, "task-1",
                "asset-2", "gs://b/q/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "b", "q/", "COLDLINE", 0,
                Instant.parse("2026-11-01T09:00:00Z"));
        done.markFailed(ArchiveRuleEntity.STATUS_APPLIED, null, Instant.parse("2026-11-01T09:30:00Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(planned, done));

        service.processArchivalTaskAsync("task-1");

        verify(executor).execute(eq(APPROVAL_ID), any());
        verify(executor, never()).execute(eq("approval-2"), any());
        verify(taskSync).sync("task-1");
    }

    @Test
    void processArchivalTaskAsync_unknownTask_isSkipped() {
        when(taskRepository.findById("nope")).thenReturn(Optional.empty());

        service.processArchivalTaskAsync("nope");

        verifyNoInteractions(executor, taskSync);
    }

    @Test
    void requestRestore_whenTheAuditLogRefusesTheIntent_doesNotStart() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.EXECUTED);
        task.setAssetStatuses(List.of(new ArchivalTaskEntity.AssetArchivalStatusData("a", null, "EXECUTED",
                "gs://b/p/", "GCP", null, null, null)));
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        when(audit.restore(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new AuditException("audit down", null));

        var out = service.requestRestore("t1", RestoreRequestDTO.builder().restoreTier("STANDARD").build());

        assertThat(out.getStatus()).isEqualTo("FAILED");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.EXECUTED);
        verify(selfProxy, never()).processRestorationAsync(anyString());
    }

    @Test
    void requestRestore_ofATaskThatIsStillPlanned_isNotRestorable() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setStatus(ArchivalStatus.PENDING_APPROVAL);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));

        var out = service.requestRestore("t1", RestoreRequestDTO.builder().build());

        assertThat(out.getStatus()).isEqualTo("NOT_RESTORABLE");
        verifyNoInteractions(audit);
    }

    @Test
    void logDryRun_neverThrowsAndNeverWrites() {
        Map<String, Object> event = approvedEvent(UUID.randomUUID().toString());
        when(assetLocationResolver.resolveForPolicyAction(anyString(), any()))
                .thenThrow(new IllegalStateException("catalog down"));

        service.logDryRun(event);

        verifyNoInteractions(executor, audit);
        verify(taskRepository, never()).save(any());
        verify(planner, never()).plan(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void providerFromLocation_mapsUriSchemesToProviders() {
        assertThat(ArchivalTaskServiceImpl.providerFromLocation("s3://bucket/key")).isEqualTo("AWS");
        assertThat(ArchivalTaskServiceImpl.providerFromLocation("arn:aws:s3:::bucket")).isEqualTo("AWS");
        assertThat(ArchivalTaskServiceImpl.providerFromLocation("gs://bucket/object")).isEqualTo("GCP");
        assertThat(ArchivalTaskServiceImpl.providerFromLocation("az://container/blob")).isEqualTo("AZURE");
        assertThat(ArchivalTaskServiceImpl.providerFromLocation(
                "https://acct.blob.core.windows.net/c/b")).isEqualTo("AZURE");
        assertThat(ArchivalTaskServiceImpl.providerFromLocation("/local/path")).isNull();
        assertThat(ArchivalTaskServiceImpl.providerFromLocation(null)).isNull();
    }

    // ------------------------------------------------------------------
    // Q-608 and Q-605: what a restore does to a native rule.
    // ------------------------------------------------------------------

    private ArchivalTaskEntity restorableTask() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("task-1");
        task.setStatus(ArchivalStatus.EXECUTED);
        task.setAssetStatuses(new java.util.ArrayList<>(List.of(new ArchivalTaskEntity.AssetArchivalStatusData(
                "asset-1", null, "EXECUTED", "gs://b/p/", "GCP", null, null, null))));
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        return task;
    }

    @Test
    void restore_removesTheRuleDlapWrote() {
        restorableTask();
        ArchiveRuleEntity applied = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        applied.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(applied));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(applied.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        assertThat(gcp.calls).contains("remove:" + applied.getRuleId());
        assertThat(gcp.rules).isEmpty();
        assertThat(applied.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REMOVED);
    }

    @Test
    void restore_neverRemovesARuleThatWasAlreadyThereBeforeDlapAppliedIt() {
        ArchivalTaskEntity task = restorableTask();
        ArchiveRuleEntity applied = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        applied.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, true, Instant.parse("2026-11-01T09:30:00Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(applied));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(applied.getRuleId()); // the customer's identical rule
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        assertThat(gcp.calls).noneMatch(c -> c.startsWith("remove:"));
        assertThat(gcp.rules).containsExactly(applied.getRuleId());
        assertThat(applied.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REMOVED);
        verify(audit).removed(eq(applied), any(), eq(Map.of("providerRuleKept", true)));
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RESTORED);
    }

    @Test
    void restore_ofAnExecutedRuleSaysObjectsThatAlreadyMovedAreNotMovedBack() {
        ArchivalTaskEntity task = restorableTask();
        ArchiveRuleEntity executed = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        executed.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        executed.markExecuted("ev-1", "storage.objects.update", Instant.parse("2026-11-02T00:00:00Z"), "PROVIDER_LOG",
                Instant.parse("2026-11-02T00:00:01Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(executed));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(executed.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        assertThat(gcp.rules).isEmpty();
        var status = task.getAssetStatuses().get(0);
        // the asset is reported as not restored and says why; the rule is gone either way
        assertThat(executed.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REMOVED);
        assertThat(status.getErrorMessage()).contains("Objects that already moved are not restored by DLAP");
    }

    @Test
    void restore_responseSaysTheRuleIsRemovedAndTransitionedObjectsAreNotMovedBack() {
        ArchivalTaskEntity task = restorableTask();
        when(taskMapper.restoreRequestDtoToData(any())).thenReturn(new ArchivalTaskEntity.RestoreRequestData());

        var out = service.requestRestore("task-1", RestoreRequestDTO.builder().restoreTier("STANDARD").build());

        assertThat(out.getMessage()).contains("restore removes the rule so no further objects move")
                .contains("already transitioned are not moved back");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RESTORATION_REQUESTED);
    }

    // ------------------------------------------------------------------
    // Q-670 and Q-674: RULE_REMOVED is a normal end state; the restore never overwrites a concurrent change.
    // ------------------------------------------------------------------

    private ArchiveRuleEntity executedRow() {
        ArchiveRuleEntity executed = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        executed.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        executed.markExecuted("ev-1", "storage.objects.update", Instant.parse("2026-11-02T00:00:00Z"), "PROVIDER_LOG",
                Instant.parse("2026-11-02T00:00:01Z"));
        return executed;
    }

    @Test
    void q670_restoringAFiredRuleEndsTheAssetAsRuleRemovedNeverRestorationFailed() {
        ArchivalTaskEntity task = restorableTask();
        ArchiveRuleEntity executed = executedRow();
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(executed));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(executed.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        var asset = task.getAssetStatuses().get(0);
        assertThat(asset.getStatus()).isEqualTo("RULE_REMOVED").isNotEqualTo("RESTORATION_FAILED");
        assertThat(asset.getErrorMessage()).contains("Objects that already moved are not restored by DLAP");
        // a normal end state is not a failure of the task either, and (Q-708) it is not "restored" either
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RULE_REMOVED);
        assertThat(task.getLastRestoreStatus()).isEqualTo("RULE_REMOVED");
        verify(audit).restore(eq("rule_removed"), eq("task-1"), eq("gs://b/p/"),
                eq(com.dlap.common.audit.AuditOutcome.SUCCEEDED), any(), any());
        verify(audit, never()).restore(eq("restore_failed"), any(), any(), any(), any(), any());
    }

    @Test
    void q670_aTaskWithOneRuleRemovedAndOneRealFailureIsPartiallyCompleted() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("task-1");
        task.setStatus(ArchivalStatus.EXECUTED);
        task.setAssetStatuses(new java.util.ArrayList<>(List.of(
                new ArchivalTaskEntity.AssetArchivalStatusData("asset-1", null, "EXECUTED", "gs://b/p/", "GCP", null, null, null),
                new ArchivalTaskEntity.AssetArchivalStatusData("asset-2", null, "EXECUTED", null, "GCP", null, null, null))));
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        ArchiveRuleEntity executed = executedRow();
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(executed));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        assertThat(task.getAssetStatuses().get(0).getStatus()).isEqualTo("RULE_REMOVED");
        assertThat(task.getAssetStatuses().get(1).getStatus()).isEqualTo("FAILED"); // missing location, a real failure
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.PARTIALLY_COMPLETED);
    }

    @Test
    void q674_aRestoreOfARowAWorkerIsStillApplyingChangesNothing() {
        ArchivalTaskEntity task = restorableTask();
        ArchiveRuleEntity claimed = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        claimed.markClaimed(Instant.parse("2026-11-01T09:30:00Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(claimed));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(claimed.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        assertThat(claimed.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("remove:"));
        verify(ruleRepository, never()).markRemovedIf(any(), any(), any());
        verify(ruleRepository, never()).save(any());
        var asset = task.getAssetStatuses().get(0);
        assertThat(asset.getStatus()).isEqualTo("RESTORATION_FAILED");
        assertThat(asset.getErrorMessage()).contains("still being applied");
        assertThat(task.getStatus()).as("the restore is not reported as done on a rule that may be live")
                .isEqualTo(ArchivalStatus.RESTORATION_FAILED);
    }

    @Test
    void q674_theRemovalIsAConditionalUpdateOnTheStatusItLoadedAndNeverASave() {
        restorableTask();
        ArchiveRuleEntity applied = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        applied.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(applied));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(applied.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        verify(ruleRepository).markRemovedIf(eq(applied.getRuleId()), eq("APPLIED"), any());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void q674_aProofThatLandedDuringTheRestoreIsFollowedOnceAndTheRowEndsRemovedFromExecuted() {
        ArchivalTaskEntity task = restorableTask();
        ArchiveRuleEntity applied = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        applied.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(applied));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(applied.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);
        // the proof service marked the row EXECUTED after the restore loaded it as APPLIED
        when(ruleRepository.markRemovedIf(eq(applied.getRuleId()), eq("APPLIED"), any())).thenReturn(0);
        when(ruleRepository.markRemovedIf(eq(applied.getRuleId()), eq("EXECUTED"), any())).thenReturn(1);
        when(ruleRepository.findById(applied.getRuleId())).thenReturn(Optional.of(executedRow()));

        service.processRestorationAsync("task-1");

        verify(ruleRepository).markRemovedIf(eq(applied.getRuleId()), eq("EXECUTED"), any());
        assertThat(applied.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_REMOVED);
        // it ended removed from EXECUTED, so the asset says objects already moved
        assertThat(task.getAssetStatuses().get(0).getStatus()).isEqualTo("RULE_REMOVED");
    }

    @Test
    void q674_aRowThatChangedToAnythingElseDuringTheRestoreIsNotOverwritten() {
        ArchivalTaskEntity task = restorableTask();
        ArchiveRuleEntity applied = row("task-1", ArchiveRuleEntity.STATUS_PLANNED);
        applied.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(applied));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        when(planner.providerFor("GCP")).thenReturn(gcp);
        when(ruleRepository.markRemovedIf(any(), any(), any())).thenReturn(0);
        ArchiveRuleEntity other = row("task-1", ArchiveRuleEntity.STATUS_REMOVED); // another restore got there first
        when(ruleRepository.findById(applied.getRuleId())).thenReturn(Optional.of(other));

        service.processRestorationAsync("task-1");

        verify(ruleRepository, never()).save(any());
        verify(audit, never()).removed(any(), any(), any());
        var asset = task.getAssetStatuses().get(0);
        assertThat(asset.getStatus()).isEqualTo("RESTORATION_FAILED");
        assertThat(asset.getErrorMessage()).contains("changed while the restore ran");
    }

    // ------------------------------------------------------------------
    // Q-708: a task whose assets all ended RULE_REMOVED has its own status.
    // ------------------------------------------------------------------

    private ArchiveRuleEntity firedRow(String ruleId, String assetId, String prefix) {
        ArchiveRuleEntity r = new ArchiveRuleEntity(ruleId, "appr-" + assetId, null, "task-1", assetId,
                "gs://b/" + prefix, "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "b", prefix, "COLDLINE", 0,
                Instant.parse("2026-11-01T09:00:00Z"));
        r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        r.markExecuted("ev-" + assetId, "storage.objects.update", Instant.parse("2026-11-02T00:00:00Z"),
                "PROVIDER_LOG", Instant.parse("2026-11-02T00:00:01Z"));
        return r;
    }

    private ArchiveRuleEntity waitingRow(String ruleId, String assetId, String prefix) {
        ArchiveRuleEntity r = firedRow(ruleId, assetId, prefix);
        // not fired yet: back to APPLIED
        r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-11-01T09:30:00Z"));
        return r;
    }

    private ArchivalTaskEntity twoAssetTask() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("task-1");
        task.setStatus(ArchivalStatus.EXECUTED);
        task.setAssetStatuses(new java.util.ArrayList<>(List.of(
                new ArchivalTaskEntity.AssetArchivalStatusData("asset-1", null, "EXECUTED", "gs://b/p/", "GCP", null, null, null),
                new ArchivalTaskEntity.AssetArchivalStatusData("asset-2", null, "EXECUTED", "gs://b/q/", "GCP", null, null, null))));
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        return task;
    }

    @Test
    void q708_aTaskWhoseAssetsAllEndedRuleRemovedGetsItsOwnStatusNotRestored() {
        ArchivalTaskEntity task = twoAssetTask();
        ArchiveRuleEntity one = firedRow("dlap-archive-aaaaaaaaaaaa", "asset-1", "p/");
        ArchiveRuleEntity two = firedRow("dlap-archive-bbbbbbbbbbbb", "asset-2", "q/");
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(one, two));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(one.getRuleId());
        gcp.rules.add(two.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        assertThat(task.getAssetStatuses()).extracting(ArchivalTaskEntity.AssetArchivalStatusData::getStatus)
                .containsExactly("RULE_REMOVED", "RULE_REMOVED");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RULE_REMOVED).isNotEqualTo(ArchivalStatus.RESTORED);
        assertThat(task.getLastRestoreStatus()).isEqualTo("RULE_REMOVED");
    }

    @Test
    void q708_aTaskWithOneAssetRestoredAndOneRuleRemovedIsStillRestored() {
        ArchivalTaskEntity task = twoAssetTask();
        ArchiveRuleEntity fired = firedRow("dlap-archive-aaaaaaaaaaaa", "asset-1", "p/");
        ArchiveRuleEntity waiting = waitingRow("dlap-archive-bbbbbbbbbbbb", "asset-2", "q/");
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(fired, waiting));
        com.dlap.autoarchival.rule.FakeRuleProvider gcp = new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true);
        gcp.rules.add(fired.getRuleId());
        gcp.rules.add(waiting.getRuleId());
        when(planner.providerFor("GCP")).thenReturn(gcp);

        service.processRestorationAsync("task-1");

        assertThat(task.getAssetStatuses()).extracting(ArchivalTaskEntity.AssetArchivalStatusData::getStatus)
                .containsExactly("RULE_REMOVED", "RESTORED");
        assertThat(task.getStatus()).as("something was restored, so RESTORED is true for the task")
                .isEqualTo(ArchivalStatus.RESTORED);
    }

    @Test
    void q708_aTaskWithOneRuleRemovedAndOneRealFailureIsStillPartiallyCompleted() {
        // the existing q670 scenario: the failure wins over a rule-removed asset
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("task-1");
        task.setStatus(ArchivalStatus.EXECUTED);
        task.setAssetStatuses(new java.util.ArrayList<>(List.of(
                new ArchivalTaskEntity.AssetArchivalStatusData("asset-1", null, "EXECUTED", "gs://b/p/", "GCP", null, null, null),
                new ArchivalTaskEntity.AssetArchivalStatusData("asset-2", null, "EXECUTED", null, "GCP", null, null, null))));
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        when(ruleRepository.findByTaskId("task-1")).thenReturn(List.of(executedRow()));
        when(planner.providerFor("GCP")).thenReturn(new com.dlap.autoarchival.rule.FakeRuleProvider("GCP", true));

        service.processRestorationAsync("task-1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.PARTIALLY_COMPLETED);
    }

    @Test
    void q708_aRuleRemovedTaskIsNotRestorableAgain() {
        ArchivalTaskEntity task = new ArchivalTaskEntity();
        task.setTaskId("task-1");
        task.setStatus(ArchivalStatus.RULE_REMOVED);
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));

        var response = service.requestRestore("task-1", new RestoreRequestDTO());

        assertThat(response.getStatus()).isEqualTo("NOT_RESTORABLE");
    }

    // ------------------------------------------------------------------
    // Q-711: a scope an identical live row already holds is already done, not a refusal.
    // ------------------------------------------------------------------

    private ArchiveRuleEntity holder() {
        ArchiveRuleEntity h = new ArchiveRuleEntity("dlap-archive-holder000000", "appr-holder", "fire-h", "task-h",
                "asset-h", "gs://b/p/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "b", "p/", "COLDLINE", 0,
                Instant.parse("2026-10-01T09:00:00Z"));
        h.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, false, Instant.parse("2026-10-01T09:30:00Z"));
        when(ruleRepository.findById("dlap-archive-holder000000")).thenReturn(Optional.of(h));
        return h;
    }

    @Test
    void q711_aPolicyFireForAScopeAlreadyHeldWritesOneAlreadyHeldRecordNotARefusalAndStoresNoTask() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        ArchiveRuleEntity holder = holder();
        when(executor.prepare(any())).thenThrow(new DuplicateScopeException("held by the other row", holder));

        service.processArchivalAction(approvedEvent(assetId));

        verify(audit).alreadyHeld("gs://b/p/", assetId, holder);
        verify(audit, never()).refused(anyString(), any(), anyString(), anyString(), any(), any());
        verify(audit, never()).refused(anyString(), any(), anyString(), anyString(), any(), any(), any());
        verify(taskRepository, never()).save(any());
        verify(executor, never()).execute(anyString(), any());
    }

    @Test
    void q711_aRecurringFireWritesTheSameAlreadyHeldCallEachTimeSoTheAuditKeyDedupes() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        ArchiveRuleEntity holder = holder();
        when(executor.prepare(any())).thenThrow(new DuplicateScopeException("held by the other row", holder));
        Map<String, Object> second = approvedEvent(assetId);
        second.put("fireId", "fire-2");
        second.put("eventId", "e2");

        service.processArchivalAction(approvedEvent(assetId));
        service.processArchivalAction(second);

        // two fires, the identical call about the holding row and asset: the audit key makes it one record
        verify(audit, org.mockito.Mockito.times(2)).alreadyHeld("gs://b/p/", assetId, holder);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void q711_anOperatorRequestForAHeldScopeIsAnAlreadyHeldTaskAndNobodyIsAskedToApprove() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        ArchiveRuleEntity holder = holder();
        when(planner.plan(anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DuplicateScopeException("held by the other row", holder));
        ArchivalTaskRequest req = ArchivalTaskRequest.builder().taskName("t")
                .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(List.of(assetId)).build()).build();

        var response = service.submitArchivalTask(req, "caller-sub");

        verify(approvalClient, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        ArchivalTaskEntity task = saved.getValue();
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.ALREADY_HELD);
        assertThat(task.getAssetStatuses().get(0).getStatus()).isEqualTo("ALREADY_HELD");
        assertThat(task.getAssetStatuses().get(0).getErrorMessage()).contains("Nothing to plan");
        assertThat(task.getAssetsFailedToArchive()).as("not a failure").isZero();
        assertThat(task.getErrorMessages()).isEmpty();
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(response.getStatus()).isEqualTo("ALREADY_HELD");
        verify(audit).alreadyHeld("gs://b/p/", assetId, holder);
        verify(audit, never()).refused(anyString(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void q711_aRequestWithOneHeldAndOneNewAssetKeepsATaskForTheNewOne() {
        String held = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(held), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        when(assetLocationResolver.resolveForPolicyAction(eq(fresh), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/q/")));
        ArchiveRuleEntity holder = holder();
        when(planner.plan(anyString(), eq("gs://b/p/"), any(), any(), any(), any()))
                .thenThrow(new DuplicateScopeException("held by the other row", holder));
        when(approvalClient.ask(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(APPROVAL_ID);
        when(executor.prepare(any())).thenReturn(row("t", ArchiveRuleEntity.STATUS_PLANNED));
        ArchivalTaskRequest req = ArchivalTaskRequest.builder().taskName("t")
                .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(List.of(held, fresh)).build()).build();

        service.submitArchivalTask(req, "caller-sub");

        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        ArchivalTaskEntity task = saved.getValue();
        assertThat(task.getAssetStatuses()).extracting(ArchivalTaskEntity.AssetArchivalStatusData::getStatus)
                .containsExactly("ALREADY_HELD", "PENDING");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.PENDING_APPROVAL);
        assertThat(task.getAssetsPendingArchival()).isEqualTo(1);
        assertThat(task.getAssetsFailedToArchive()).isZero();
    }

    @Test
    void q711_aHeldAssetBesideARealRefusalLeavesTheTaskRefusedNotAlreadyHeld() {
        String held = UUID.randomUUID().toString();
        String bad = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(held), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        when(assetLocationResolver.resolveForPolicyAction(eq(bad), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/q/")));
        ArchiveRuleEntity holder = holder();
        when(planner.plan(anyString(), eq("gs://b/p/"), any(), any(), any(), any()))
                .thenThrow(new DuplicateScopeException("held by the other row", holder));
        when(planner.plan(anyString(), eq("gs://b/q/"), any(), any(), any(), any()))
                .thenThrow(new RuleException(RuleException.Reason.INVALID_TIER, "no such tier"));
        ArchivalTaskRequest req = ArchivalTaskRequest.builder().taskName("t")
                .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(List.of(held, bad)).build()).build();

        service.submitArchivalTask(req, "caller-sub");

        ArgumentCaptor<ArchivalTaskEntity> saved = ArgumentCaptor.forClass(ArchivalTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ArchivalStatus.REFUSED);
        assertThat(saved.getValue().getAssetsFailedToArchive()).isEqualTo(1);
        assertThat(saved.getValue().getErrorMessages()).hasSize(1).first().asString().contains("INVALID_TIER");
    }

    @Test
    void q711_anAuditFailureOnTheAlreadyHeldRecordNeitherThrowsNorChangesTheOutcome() {
        String assetId = UUID.randomUUID().toString();
        when(assetLocationResolver.resolveForPolicyAction(eq(assetId), any()))
                .thenReturn(Optional.of(new AssetLocation("GCP", "gs://b/p/")));
        ArchiveRuleEntity holder = holder();
        when(executor.prepare(any())).thenThrow(new DuplicateScopeException("held by the other row", holder));
        when(audit.alreadyHeld(anyString(), anyString(), any())).thenThrow(new AuditException("down", null));

        service.processArchivalAction(approvedEvent(assetId));

        verify(taskRepository, never()).save(any());
        verify(executor, never()).execute(anyString(), any());
    }
}
