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

package com.dlap.autodelete.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.ApprovalCreateRequest;
import com.dlap.autodelete.dto.ApprovalViewDTO;
import com.dlap.autodelete.dto.DeletionTaskListResponse;
import com.dlap.autodelete.dto.DeletionTaskRequest;
import com.dlap.autodelete.dto.DeletionTaskResponse;
import com.dlap.autodelete.dto.DeletionTaskStatusDTO;
import com.dlap.autodelete.mapper.DeletionTaskMapper;
import com.dlap.autodelete.model.DeletionStatus;
import com.dlap.autodelete.model.DeletionTaskEntity;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.ApprovalClient;
import com.dlap.autodelete.retire.RefusalReason;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.autodelete.retire.RetireRemoteException;
import com.dlap.autodelete.retire.TaskApprovalProcessor;
import com.dlap.autodelete.service.exception.DeletionTaskNotFoundException;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * RET-02: the task API asks for approvals and never deletes, ignores the body's requester and
 * approval override, and no longer decides approvals locally.
 */
class DeletionTaskServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String SUB = "7d0e6c55-0000-4000-8000-00000000a11c";

    private final DeletionTaskRepository taskRepository = mock(DeletionTaskRepository.class);
    private final DeletionTaskMapper taskMapper = mock(DeletionTaskMapper.class);
    private final SoftDeleteRecordRepository softDeleteRecordRepository = mock(SoftDeleteRecordRepository.class);
    private final RetireDispositionRepository dispositions = mock(RetireDispositionRepository.class);
    private final ApprovalClient approvals = mock(ApprovalClient.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private DeletionTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeletionTaskServiceImpl(taskRepository, taskMapper, softDeleteRecordRepository, dispositions,
                approvals, audit, RetireProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(taskMapper.requestToEntity(any())).thenAnswer(inv -> new DeletionTaskEntity());
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskMapper.entityToTaskResponse(any())).thenAnswer(inv -> {
            DeletionTaskEntity e = inv.getArgument(0);
            return DeletionTaskResponse.builder().taskId(e.getTaskId()).status(e.getStatus().name()).build();
        });
    }

    private static DeletionTaskRequest request(List<String> ids, DeletionTaskRequest.DeletionConfigOverride override) {
        return DeletionTaskRequest.builder()
                .taskName("cleanup")
                .triggeredBy("someone-else")
                .justification("retention reached")
                .scope(DeletionTaskRequest.DeletionScope.builder().assetIds(ids).build())
                .overrideConfig(override)
                .build();
    }

    private static ApprovalViewDTO view(UUID id) {
        return new ApprovalViewDTO(id, "PENDING", false, "API", "SOFT_DELETE", "delete", UUID.randomUUID(), null, null,
                SUB, "dlap-backend", List.of(SUB), 1, 0, null, List.of());
    }

    @Test
    void aSubmissionAsksForOneApprovalPerAssetOnBehalfOfTheCallerAndDeletesNothing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID approvalA = UUID.randomUUID();
        UUID approvalB = UUID.randomUUID();
        when(approvals.create(any())).thenReturn(view(approvalA), view(approvalB));

        DeletionTaskResponse response = service.submitDeletionTask(request(List.of(a.toString(), b.toString()), null), SUB);

        ArgumentCaptor<ApprovalCreateRequest> asked = ArgumentCaptor.forClass(ApprovalCreateRequest.class);
        verify(approvals, org.mockito.Mockito.times(2)).create(asked.capture());
        assertThat(asked.getAllValues()).allSatisfy(r -> {
            assertThat(r.actionClass()).isEqualTo("SOFT_DELETE");
            assertThat(r.onBehalfOf()).as("the requester is excluded from approving").isEqualTo(SUB);
            assertThat(r.idempotencyKey()).startsWith("retire.delete:task:");
        });
        assertThat(asked.getAllValues()).extracting(ApprovalCreateRequest::assetId).containsExactly(a, b);
        ArgumentCaptor<DeletionTaskEntity> saved = ArgumentCaptor.forClass(DeletionTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DeletionStatus.PENDING_APPROVAL);
        assertThat(saved.getValue().getRequestedBy()).isEqualTo(SUB);
        assertThat(saved.getValue().getTriggeredBy()).as("the body's triggeredBy is ignored").isEqualTo(SUB);
        assertThat(saved.getValue().getAssetStatuses()).extracting(DeletionTaskEntity.AssetDeletionStatusData::getApprovalId)
                .containsExactly(approvalA.toString(), approvalB.toString());
        assertThat(saved.getValue().getAssetStatuses()).extracting(DeletionTaskEntity.AssetDeletionStatusData::getStatus)
                .containsOnly(TaskApprovalProcessor.AWAITING_APPROVAL);
        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        verify(audit, org.mockito.Mockito.times(2)).record(eq(RetireAuditLog.TASK_REQUESTED), eq(NOW),
                eq(AuditActor.user(SUB)), any(), eq(AuditOutcome.REQUESTED), any(), any());
    }

    @Test
    void theRequesterCannotSkipApprovalWithAnOverride() {
        UUID a = UUID.randomUUID();
        when(approvals.create(any())).thenReturn(view(UUID.randomUUID()));
        DeletionTaskRequest.DeletionConfigOverride noApproval = DeletionTaskRequest.DeletionConfigOverride.builder()
                .requireApproval(false).build();

        service.submitDeletionTask(request(List.of(a.toString()), noApproval), SUB);

        verify(approvals).create(any());
        ArgumentCaptor<DeletionTaskEntity> saved = ArgumentCaptor.forClass(DeletionTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DeletionStatus.PENDING_APPROVAL);
    }

    @Test
    void aHardDeleteTaskAsksForTwoApprovers() {
        when(approvals.create(any())).thenReturn(view(UUID.randomUUID()));
        DeletionTaskRequest.DeletionConfigOverride hard = DeletionTaskRequest.DeletionConfigOverride.builder()
                .softDelete(false).build();

        service.submitDeletionTask(request(List.of(UUID.randomUUID().toString()), hard), SUB);

        ArgumentCaptor<ApprovalCreateRequest> asked = ArgumentCaptor.forClass(ApprovalCreateRequest.class);
        verify(approvals).create(asked.capture());
        assertThat(asked.getValue().actionClass()).isEqualTo("HARD_DELETE");
        assertThat(asked.getValue().requiredApprovals()).isEqualTo(2);
    }

    @Test
    void aNonUuidAssetIsRefusedAndAnUnreachablePolicyEngineFailsTheAsset() {
        when(approvals.create(any())).thenThrow(new RetireRemoteException("down", true, null));

        DeletionTaskResponse response = service.submitDeletionTask(
                request(List.of("gs://bucket/file", UUID.randomUUID().toString()), null), SUB);

        ArgumentCaptor<DeletionTaskEntity> saved = ArgumentCaptor.forClass(DeletionTaskEntity.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getAssetStatuses()).extracting(DeletionTaskEntity.AssetDeletionStatusData::getStatus)
                .containsExactly(TaskApprovalProcessor.REFUSED, TaskApprovalProcessor.FAILED);
        assertThat(saved.getValue().getStatus()).isEqualTo(DeletionStatus.FAILED);
        assertThat(response.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void aSubmissionWithoutSubjectOrScopeIsAValidationFailure() {
        assertThat(service.submitDeletionTask(request(List.of(UUID.randomUUID().toString()), null), null).getStatus())
                .isEqualTo("FAILED_VALIDATION");
        assertThat(service.submitDeletionTask(request(List.of(), null), SUB).getStatus())
                .isEqualTo("FAILED_VALIDATION");
        verify(approvals, never()).create(any());
    }

    @Test
    void localApproveAndRejectNoLongerDecideAnything() {
        DeletionTaskEntity task = new DeletionTaskEntity();
        task.setTaskId("t1");
        task.setStatus(DeletionStatus.PENDING_APPROVAL);
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        when(taskMapper.entityToStatusDTO(task)).thenAnswer(inv -> DeletionTaskStatusDTO.builder().taskId("t1")
                .status("PENDING_APPROVAL").build());

        DeletionTaskStatusDTO approved = service.approveTask("t1", null);
        DeletionTaskStatusDTO rejected = service.rejectTask("t1", null);

        assertThat(approved.getErrorMessages()).containsExactly(DeletionTaskServiceImpl.DECIDE_IN_POLICY_ENGINE);
        assertThat(rejected.getErrorMessages()).containsExactly(DeletionTaskServiceImpl.DECIDE_IN_POLICY_ENGINE);
        assertThat(task.getStatus()).isEqualTo(DeletionStatus.PENDING_APPROVAL);
        verify(taskRepository, never()).save(any());
        when(taskRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approveTask("nope", null)).isInstanceOf(DeletionTaskNotFoundException.class);
    }

    @Test
    void theListMapsEveryExecutorStateOntoTheUiVocabulary() {
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.SOFT_DELETED))
                .isEqualTo("QUEUED");
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL))
                .isEqualTo("QUEUED");
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.AWAITING_PROOF))
                .as("applied but not proven is not complete").isEqualTo("IN_PROGRESS");
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.HARD_DELETED))
                .isEqualTo("COMPLETED");
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.HARD_DELETE_REFUSED))
                .isEqualTo("REJECTED");
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.HARD_DELETE_FAILED))
                .isEqualTo("FAILED");
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.RESTORED))
                .isEqualTo("CANCELLED");
    }

    /** Q-636: a dry-run record is listed as DRY_RUN, never COMPLETED, and its error field stays empty. */
    @Test
    void aDryRunRecordIsListedAsDryRunNotCompletedAndWithoutAnErrorMessage() {
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.DRY_RUN_COMPLETE))
                .isEqualTo("DRY_RUN").isNotEqualTo("COMPLETED");
        SoftDeleteRecordEntity dry = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("gs://b").status(SoftDeleteRecordEntity.DRY_RUN_COMPLETE).lastError("DRY-RUN: no cloud call")
                .queuedAt(NOW).updatedAt(NOW).build();
        Pageable page = PageRequest.of(0, 20);
        when(softDeleteRecordRepository.findAllByOrderByQueuedAtDesc(page)).thenReturn(new PageImpl<>(List.of(dry), page, 1));

        DeletionTaskListResponse list = service.listTasks(page);

        assertThat(list.getTasks()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo("DRY_RUN");
            assertThat(row.getErrorMessages()).as("the error field stays empty").isNull();
            assertThat(row.getAssetsProcessedSuccessfully()).as("nothing was deleted").isZero();
            assertThat(row.getAssetsPendingProcessing()).isZero();
        });
    }

    /** Q-631: a rule in force is listed as RULE_IN_FORCE, not as a completed delete, and shows the window end. */
    @Test
    void aRuleInForceIsListedAsRuleInForceNotCompleted() {
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.RULE_IN_FORCE))
                .isEqualTo("RULE_IN_FORCE");
        SoftDeleteRecordEntity rule = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("s3://bucket").status(SoftDeleteRecordEntity.RULE_IN_FORCE)
                .scheduledHardDeleteAt(NOW.plusSeconds(60)).queuedAt(NOW).updatedAt(NOW).build();

        DeletionTaskStatusDTO row = DeletionTaskServiceImpl.softDeleteToStatusDto(rule);

        assertThat(row.getStatus()).isEqualTo("RULE_IN_FORCE");
        assertThat(row.getAssetsProcessedSuccessfully()).isZero();
        assertThat(row.getScheduledHardDeleteAt()).isEqualTo(NOW.plusSeconds(60));
    }

    /**
     * Q-677 (RET-06): the task list shows each rule in force with its age (whole days since the rule was applied)
     * and when the executor ends it; other rows carry no age.
     */
    @Test
    void theTaskListShowsTheAgeOfEachRuleInForce() {
        SoftDeleteRecordEntity rule = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("s3://bucket").status(SoftDeleteRecordEntity.RULE_IN_FORCE).queuedAt(NOW).updatedAt(NOW)
                .build();
        SoftDeleteRecordEntity waiting = SoftDeleteRecordEntity.builder().id(UUID.randomUUID())
                .assetId(UUID.randomUUID()).assetUri("gs://other").status(SoftDeleteRecordEntity.SOFT_DELETED)
                .queuedAt(NOW).updatedAt(NOW).build();
        Instant applied = NOW.minus(java.time.Duration.ofDays(12)).minusSeconds(3600);
        RetireDispositionEntity d = RetireDispositionEntity.builder().id(UUID.randomUUID())
                .softDeleteRecordId(rule.getId()).appliedAt(applied).status(RetireDispositionEntity.EXECUTED).build();
        Pageable page = PageRequest.of(0, 20);
        when(softDeleteRecordRepository.findAllByOrderByQueuedAtDesc(page))
                .thenReturn(new PageImpl<>(List.of(rule, waiting), page, 2));
        when(dispositions.findBySoftDeleteRecordIdIn(List.of(rule.getId()))).thenReturn(List.of(d));

        DeletionTaskListResponse list = service.listTasks(page);

        assertThat(list.getTasks()).hasSize(2);
        DeletionTaskStatusDTO inForce = list.getTasks().get(0);
        assertThat(inForce.getStatus()).isEqualTo("RULE_IN_FORCE");
        assertThat(inForce.getRuleAppliedAt()).isEqualTo(applied);
        assertThat(inForce.getRuleAgeDays()).isEqualTo(12L);
        assertThat(inForce.getRuleEndsAt()).isEqualTo(applied.plus(java.time.Duration.ofDays(30)));
        DeletionTaskStatusDTO other = list.getTasks().get(1);
        assertThat(other.getRuleAgeDays()).isNull();
        assertThat(other.getRuleAppliedAt()).isNull();
    }

    /** UI-04 (Q-623): every row from a soft-delete record carries the end of its window; none gives null. */
    @Test
    void theTaskListCarriesTheEndOfEachRecordsSoftDeleteWindow() {
        Instant closes = NOW.plus(java.time.Duration.ofDays(7));
        SoftDeleteRecordEntity inWindow = SoftDeleteRecordEntity.builder().id(UUID.randomUUID())
                .assetId(UUID.randomUUID()).assetUri("gs://a").status(SoftDeleteRecordEntity.SOFT_DELETED)
                .scheduledHardDeleteAt(closes).queuedAt(NOW).updatedAt(NOW).build();
        SoftDeleteRecordEntity noWindow = SoftDeleteRecordEntity.builder().id(UUID.randomUUID())
                .assetId(UUID.randomUUID()).assetUri("gs://b").status(SoftDeleteRecordEntity.SOFT_DELETED)
                .queuedAt(NOW).updatedAt(NOW).build();
        Pageable page = PageRequest.of(0, 20);
        when(softDeleteRecordRepository.findAllByOrderByQueuedAtDesc(page))
                .thenReturn(new PageImpl<>(List.of(inWindow, noWindow), page, 2));

        DeletionTaskListResponse list = service.listTasks(page);

        assertThat(list.getTasks()).extracting(DeletionTaskStatusDTO::getScheduledHardDeleteAt)
                .containsExactly(closes, null);
    }

    /**
     * UI-04 (Q-746): an applied container rule that still waits for its proof shows its age and end like a rule in
     * force, so the page can offer the restore that removes it; an engine delete waiting for proof, a dry-run
     * disposition and a rule without a stored document show none.
     */
    @Test
    void anAppliedContainerRuleAwaitingProofShowsItsAgeAndAnEngineDeleteDoesNot() {
        Instant applied = NOW.minus(java.time.Duration.ofDays(3)).minusSeconds(60);
        SoftDeleteRecordEntity rule = awaitingProof("s3://bucket");
        SoftDeleteRecordEntity engine = awaitingProof("s3://bucket/key");
        SoftDeleteRecordEntity dry = awaitingProof("gs://dry");
        SoftDeleteRecordEntity noDoc = awaitingProof("gs://nodoc");
        SoftDeleteRecordEntity table = awaitingProof("bq://p.d.t");
        Pageable page = PageRequest.of(0, 20);
        when(softDeleteRecordRepository.findAllByOrderByQueuedAtDesc(page))
                .thenReturn(new PageImpl<>(List.of(rule, engine, dry, noDoc, table), page, 5));
        when(dispositions.findBySoftDeleteRecordIdIn(List.of(rule.getId(), engine.getId(), dry.getId(), noDoc.getId(),
                table.getId()))).thenReturn(List.of(
                        disposition(rule, RetireDispositionEntity.MODE_NATIVE_RULE, "S3_LIFECYCLE", false, "{}", applied),
                        disposition(engine, RetireDispositionEntity.MODE_DIRECT, "ENGINE_AWS", false, null, applied),
                        disposition(dry, RetireDispositionEntity.MODE_NATIVE_RULE, "GCS_LIFECYCLE", true, "{}", applied),
                        disposition(noDoc, RetireDispositionEntity.MODE_NATIVE_RULE, "GCS_LIFECYCLE", false, null,
                                applied),
                        disposition(table, RetireDispositionEntity.MODE_NATIVE_RULE, "BIGQUERY_TABLE_EXPIRATION", false,
                                "{}", applied)));

        List<DeletionTaskStatusDTO> rows = service.listTasks(page).getTasks();

        assertThat(rows).extracting(DeletionTaskStatusDTO::getStatus).containsOnly("IN_PROGRESS");
        assertThat(rows.get(0).getRuleAppliedAt()).isEqualTo(applied);
        assertThat(rows.get(0).getRuleAgeDays()).isEqualTo(3L);
        assertThat(rows.get(0).getRuleEndsAt()).isEqualTo(applied.plus(java.time.Duration.ofDays(30)));
        assertThat(rows.subList(1, 5)).allSatisfy(row -> {
            assertThat(row.getRuleAppliedAt()).isNull();
            assertThat(row.getRuleAgeDays()).isNull();
            assertThat(row.getRuleEndsAt()).isNull();
        });
    }

    private static SoftDeleteRecordEntity awaitingProof(String uri) {
        return SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID()).assetUri(uri)
                .status(SoftDeleteRecordEntity.AWAITING_PROOF).queuedAt(NOW).updatedAt(NOW).build();
    }

    private static RetireDispositionEntity disposition(SoftDeleteRecordEntity rec, String mode, String platform,
            boolean dryRun, String ruleDocument, Instant appliedAt) {
        return RetireDispositionEntity.builder().id(UUID.randomUUID()).softDeleteRecordId(rec.getId()).mode(mode)
                .platform(platform).dryRun(dryRun).ruleDocument(ruleDocument).appliedAt(appliedAt)
                .status(RetireDispositionEntity.EXECUTED).build();
    }

    /** RET-06: an ended rule and a rule DLAP could not find have their own list states. */
    @Test
    void anEndedRuleAndARestoreFailedRecordHaveTheirOwnListStates() {
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.RULE_ENDED))
                .isEqualTo("RULE_ENDED");
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.RESTORE_FAILED))
                .isEqualTo("RESTORE_FAILED");
        SoftDeleteRecordEntity failed = SoftDeleteRecordEntity.builder().id(UUID.randomUUID())
                .assetId(UUID.randomUUID()).assetUri("gs://b").status(SoftDeleteRecordEntity.RESTORE_FAILED)
                .lastError("rule-not-found: no rule matches").queuedAt(NOW).updatedAt(NOW).build();

        DeletionTaskStatusDTO row = DeletionTaskServiceImpl.softDeleteToStatusDto(failed);

        assertThat(row.getAssetsFailedToProcess()).isEqualTo(1);
        assertThat(row.getErrorMessages()).containsExactly("rule-not-found: no rule matches");
        assertThat(row.getCompletedAt()).isNotNull();
    }

    /**
     * Q-695 (RET-08): an applied rule that was never proven and was removed at its maximum age has its own list
     * state, RULE_ENDED_UNPROVEN: ended (a completion time), neither completed nor failed nor pending.
     */
    @Test
    void anUnprovenRuleThatEndedHasItsOwnListState() {
        assertThat(DeletionTaskServiceImpl.mapSoftDeleteStatus(SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN))
                .isEqualTo("RULE_ENDED_UNPROVEN");
        SoftDeleteRecordEntity ended = SoftDeleteRecordEntity.builder().id(UUID.randomUUID())
                .assetId(UUID.randomUUID()).assetUri("s3://b").status(SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN)
                .queuedAt(NOW).updatedAt(NOW).build();

        DeletionTaskStatusDTO row = DeletionTaskServiceImpl.softDeleteToStatusDto(ended);

        assertThat(row.getStatus()).isEqualTo("RULE_ENDED_UNPROVEN");
        assertThat(row.getCompletedAt()).isNotNull();
        assertThat(row.getAssetsProcessedSuccessfully()).isZero();
        assertThat(row.getAssetsFailedToProcess()).isZero();
        assertThat(row.getAssetsPendingProcessing()).isZero();
    }

    /** Q-632: a refused submission (a non-UUID asset id, an approval that could not be requested) is audited. */
    @Test
    void everyRefusedAssetOfASubmissionWritesARefusalRecord() {
        when(approvals.create(any())).thenThrow(new RetireRemoteException("down", true, null));
        UUID real = UUID.randomUUID();

        service.submitDeletionTask(request(List.of("gs://bucket/file", real.toString()), null), SUB);

        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        verify(audit).refused(eq(NOW), eq(AuditActor.user(SUB)), eq("gs://bucket/file"), taskId.capture(), eq("task"),
                eq(RefusalReason.INVALID), any(), any());
        verify(audit).refused(eq(NOW), eq(AuditActor.user(SUB)), eq(real.toString()), eq(taskId.getValue()), eq("task"),
                eq(RefusalReason.REMOTE_REFUSED), any(), any());
    }

    /** Q-632: an absurdly long asset id can neither fail the audit append nor flood the log. */
    @Test
    void aVeryLongAssetIdIsCutBeforeItBecomesAnAuditTarget() {
        String longId = "x".repeat(5000);

        service.submitDeletionTask(request(List.of(longId), null), SUB);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(audit).refused(any(), any(), target.capture(), any(), eq("task"), eq(RefusalReason.INVALID), any(), any());
        assertThat(target.getValue()).hasSize(512);
    }
}
