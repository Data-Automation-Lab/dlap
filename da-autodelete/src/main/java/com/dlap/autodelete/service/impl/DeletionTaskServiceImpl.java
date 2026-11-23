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

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.ApprovalCreateRequest;
import com.dlap.autodelete.dto.ApprovalViewDTO;
import com.dlap.autodelete.dto.DeletionTaskListResponse;
import com.dlap.autodelete.dto.DeletionTaskRequest;
import com.dlap.autodelete.dto.DeletionTaskResponse;
import com.dlap.autodelete.dto.DeletionTaskStatusDTO;
import com.dlap.autodelete.dto.TaskApprovalRequest;
import com.dlap.autodelete.mapper.DeletionTaskMapper;
import com.dlap.autodelete.model.DeletionStatus;
import com.dlap.autodelete.model.DeletionTaskEntity;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.ApprovalClient;
import com.dlap.autodelete.retire.ApprovalVerifier;
import com.dlap.autodelete.retire.RefusalReason;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.autodelete.retire.RetireRemoteException;
import com.dlap.autodelete.retire.TaskApprovalProcessor;
import com.dlap.autodelete.service.IDeletionTaskService;
import com.dlap.autodelete.service.exception.DeletionTaskNotFoundException;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The task API (RET-02).
 *
 * <p>A submission never deletes anything by itself and cannot opt out of approval: the request
 * body's {@code triggeredBy} and {@code overrideConfig.requireApproval} are ignored. The requester
 * is the caller's JWT subject. For every asset the service asks the policy engine for an approval
 * (class {@code SOFT_DELETE}, or {@code HARD_DELETE} when the task asks for a hard delete) on behalf
 * of the requester, so the requester can never approve it. The retire sweeper admits each asset
 * into its soft-delete window once its approval is executable ({@link TaskApprovalProcessor}); the
 * hard delete at the end of the window asks for its own two-person approval.
 *
 * <p>The local approve and reject endpoints no longer decide anything: decisions are made in the
 * policy engine's approval API, where separation of duties is enforced.
 */
@Service("deletionTaskService")
@Slf4j
public class DeletionTaskServiceImpl implements IDeletionTaskService {

    /** Message of the retired local approve and reject endpoints. */
    public static final String DECIDE_IN_POLICY_ENGINE = "Deletion approvals are decided in the policy engine: "
            + "POST /api/v1/policyengine/approvals/{approvalId}/decisions (the approval ids are in this task's "
            + "asset statuses).";

    private final DeletionTaskRepository taskRepository;
    private final DeletionTaskMapper taskMapper;
    private final SoftDeleteRecordRepository softDeleteRecordRepository;
    private final RetireDispositionRepository dispositions;
    private final ApprovalClient approvals;
    private final RetireAuditLog audit;
    private final RetireProperties properties;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param taskRepository             the tasks
     * @param taskMapper                 the mapper
     * @param softDeleteRecordRepository the soft-delete rows
     * @param dispositions               the dispositions (the age of a rule in force)
     * @param approvals                  the approval API
     * @param audit                      the audit log
     * @param properties                 the settings
     * @param clock                      the clock
     */
    public DeletionTaskServiceImpl(DeletionTaskRepository taskRepository, DeletionTaskMapper taskMapper,
            SoftDeleteRecordRepository softDeleteRecordRepository, RetireDispositionRepository dispositions,
            ApprovalClient approvals, RetireAuditLog audit, RetireProperties properties, Clock clock) {
        this.taskRepository = taskRepository;
        this.taskMapper = taskMapper;
        this.softDeleteRecordRepository = softDeleteRecordRepository;
        this.dispositions = dispositions;
        this.approvals = approvals;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DeletionTaskResponse submitDeletionTask(DeletionTaskRequest request, String requesterSubject) {
        Instant now = clock.instant();
        LocalDateTime submittedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        if (requesterSubject == null || requesterSubject.isBlank()) {
            return failed(request, submittedAt, "The request has no authenticated subject.");
        }
        if (request.getScope() == null || request.getScope().getAssetIds() == null
                || request.getScope().getAssetIds().isEmpty()) {
            return failed(request, submittedAt, "Deletion scope must contain at least one asset ID.");
        }
        List<String> assetIds = request.getScope().getAssetIds();
        if (assetIds.size() > properties.maxTaskAssets()) {
            return failed(request, submittedAt, "A task may name at most " + properties.maxTaskAssets() + " assets.");
        }

        DeletionTaskEntity entity = taskMapper.requestToEntity(request);
        String taskId = UUID.randomUUID().toString();
        entity.setTaskId(taskId);
        entity.setSubmittedAt(submittedAt);
        entity.setRequestedBy(requesterSubject);
        entity.setTriggeredBy(requesterSubject);
        boolean hard = request.getOverrideConfig() != null && Boolean.FALSE.equals(request.getOverrideConfig()
                .getSoftDelete());

        List<DeletionTaskEntity.AssetDeletionStatusData> rows = new ArrayList<>();
        for (String raw : assetIds) {
            DeletionTaskEntity.AssetDeletionStatusData row = new DeletionTaskEntity.AssetDeletionStatusData(raw, null,
                    TaskApprovalProcessor.AWAITING_APPROVAL, hard ? "HARD" : "SOFT", null, null);
            rows.add(row);
            UUID assetId = parseUuid(raw);
            if (assetId == null) {
                row.setStatus(TaskApprovalProcessor.REFUSED);
                row.setErrorMessage(RefusalReason.INVALID.code() + ": the asset id must be a catalog asset UUID");
                row.setProcessedAt(submittedAt);
                // Every refusal is a record (Q-632).
                audit.refused(now, AuditActor.user(requesterSubject), RetireAuditLog.safeTarget(raw), taskId, "task",
                        RefusalReason.INVALID, "the asset id must be a catalog asset UUID", Map.of("taskId", taskId));
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("taskId", taskId);
            if (request.getOverrideConfig() != null && request.getOverrideConfig().getSoftDeleteRetentionDays() != null) {
                params.put("softDeleteWindowDays", request.getOverrideConfig().getSoftDeleteRetentionDays());
            }
            String reason = request.getJustification() == null || request.getJustification().isBlank()
                    ? "Deletion task " + taskId + (request.getTaskName() == null ? "" : " (" + request.getTaskName() + ")")
                    : request.getJustification();
            try {
                ApprovalViewDTO view = approvals.create(new ApprovalCreateRequest(
                        hard ? ApprovalVerifier.HARD_DELETE : ApprovalVerifier.SOFT_DELETE, "delete", assetId, null,
                        null, null, params, reason,
                        hard ? properties.hardDeleteRequiredApprovals() : null,
                        "retire.delete:task:" + taskId + ":" + assetId, requesterSubject));
                row.setApprovalId(view.id().toString());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("taskId", taskId);
                details.put("assetId", assetId);
                details.put("approvalId", view.id());
                details.put("actionClass", hard ? ApprovalVerifier.HARD_DELETE : ApprovalVerifier.SOFT_DELETE);
                audit.record(RetireAuditLog.TASK_REQUESTED, now, AuditActor.user(requesterSubject), assetId.toString(),
                        AuditOutcome.REQUESTED, taskId, details);
            } catch (RetireRemoteException e) {
                log.warn("Task {}: the approval for asset {} could not be requested: {}", taskId, assetId,
                        e.getMessage());
                row.setStatus(TaskApprovalProcessor.FAILED);
                row.setErrorMessage("approval-not-requested: " + e.getMessage());
                row.setProcessedAt(submittedAt);
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("taskId", taskId);
                failure.put("retryable", e.isRetryable());
                audit.refused(now, AuditActor.user(requesterSubject), assetId.toString(), taskId, "task",
                        RefusalReason.REMOTE_REFUSED, "approval-not-requested: " + e.getMessage(), failure);
            }
        }
        long waiting = rows.stream().filter(r -> TaskApprovalProcessor.AWAITING_APPROVAL.equals(r.getStatus())).count();
        entity.setAssetStatuses(rows);
        entity.setTotalAssetsInScope(rows.size());
        entity.setAssetsPendingProcessing((int) waiting);
        entity.setAssetsFailedToProcess(rows.size() - (int) waiting);
        entity.setAssetsProcessedSuccessfully(0);
        entity.setStatus(waiting > 0 ? DeletionStatus.PENDING_APPROVAL : DeletionStatus.FAILED);
        if (waiting == 0) {
            entity.setCompletedAt(submittedAt);
        }
        DeletionTaskEntity saved = taskRepository.save(entity);
        log.info("Deletion task {} submitted by {}: {} asset(s) wait for approval", taskId, requesterSubject, waiting);
        DeletionTaskResponse response = taskMapper.entityToTaskResponse(saved);
        response.setMessage(waiting > 0 ? "Approvals requested; decide them in the approval inbox."
                : "No approval could be requested; see the asset statuses.");
        return response;
    }

    private static DeletionTaskResponse failed(DeletionTaskRequest request, LocalDateTime at, String message) {
        return DeletionTaskResponse.builder()
                .taskId(null)
                .taskName(request.getTaskName())
                .status("FAILED_VALIDATION")
                .submittedAt(at)
                .message(message)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DeletionTaskStatusDTO getTaskStatus(String taskId) {
        return taskRepository.findById(taskId)
                .map(taskMapper::entityToStatusDTO)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public DeletionTaskListResponse listTasks(Pageable pageable) {
        // The policy-driven DELETE path writes one row per asset into soft_delete_records; the
        // task table only holds tasks submitted through the task API. Read the real table so queued,
        // soft-deleted and hard-deleted assets show up here.
        Page<SoftDeleteRecordEntity> recordPage = softDeleteRecordRepository.findAllByOrderByQueuedAtDesc(pageable);
        // RET-06 (Q-677): each rule in force shows its age and when the executor ends it. UI-04 (Q-746): so does an
        // applied container rule that still waits for its proof, which also ends at the maximum age and which a
        // restore removes just the same.
        List<UUID> withRule = recordPage.getContent().stream()
                .filter(r -> SoftDeleteRecordEntity.RULE_IN_FORCE.equals(r.getStatus())
                        || SoftDeleteRecordEntity.AWAITING_PROOF.equals(r.getStatus()))
                .map(SoftDeleteRecordEntity::getId)
                .toList();
        Map<UUID, RetireDispositionEntity> rules = new HashMap<>();
        if (!withRule.isEmpty()) {
            for (RetireDispositionEntity d : dispositions.findBySoftDeleteRecordIdIn(withRule)) {
                rules.put(d.getSoftDeleteRecordId(), d);
            }
        }
        Instant now = clock.instant();
        List<DeletionTaskStatusDTO> dtoList = recordPage.getContent().stream()
                .map(r -> withRuleAge(softDeleteToStatusDto(r), rules.get(r.getId()), now,
                        properties.ruleInForce().maxAge()))
                .collect(Collectors.toList());
        return DeletionTaskListResponse.fromPage(new PageImpl<>(dtoList, pageable, recordPage.getTotalElements()));
    }

    /** List status of a record whose hard delete only ran under dry run: nothing was deleted (Q-636). */
    public static final String STATUS_DRY_RUN = "DRY_RUN";
    /** List status of a record whose bucket, prefix or container rule is proven to fire (Q-631). */
    public static final String STATUS_RULE_IN_FORCE = "RULE_IN_FORCE";
    /** List status of a record whose rule reached its maximum age and was removed (RET-06, Q-677). */
    public static final String STATUS_RULE_ENDED = "RULE_ENDED";
    /** List status of a record whose applied rule was never proven and was removed at its maximum age (Q-695). */
    public static final String STATUS_RULE_ENDED_UNPROVEN = "RULE_ENDED_UNPROVEN";
    /** List status of a record whose rule DLAP could not find to remove (RET-06, Q-682). */
    public static final String STATUS_RESTORE_FAILED = "RESTORE_FAILED";

    /**
     * Adds the age of a rule (RET-06 Q-677, UI-04 Q-746): when its rule was applied, how many whole days ago, and
     * when the executor removes it ({@code dlap.autodelete.rule-in-force.max-age} after the apply). A
     * {@value #STATUS_RULE_IN_FORCE} row shows it for any applied disposition. An {@code IN_PROGRESS} row (the
     * record is {@code AWAITING_PROOF}) shows it only for an applied container rule, the same test the restore
     * uses, so the row tells the page that a restore removes a rule; an engine delete or a table expiration that
     * waits for its proof carries none.
     *
     * @param dto    the row
     * @param rule   the record's disposition, or null
     * @param now    the time of the list
     * @param maxAge the maximum age of a rule
     * @return the same row
     */
    static DeletionTaskStatusDTO withRuleAge(DeletionTaskStatusDTO dto, RetireDispositionEntity rule, Instant now,
            Duration maxAge) {
        if (rule == null || rule.getAppliedAt() == null) {
            return dto;
        }
        boolean inForce = STATUS_RULE_IN_FORCE.equals(dto.getStatus());
        boolean appliedContainerRuleAwaitingProof = DeletionStatus.IN_PROGRESS.name().equals(dto.getStatus())
                && RetireDispositionEntity.MODE_NATIVE_RULE.equals(rule.getMode())
                && NativeRulePlatform.isContainerRule(rule.getPlatform())
                && !rule.isDryRun()
                && rule.getRuleDocument() != null;
        if (!inForce && !appliedContainerRuleAwaitingProof) {
            return dto;
        }
        Duration age = Duration.between(rule.getAppliedAt(), now);
        dto.setRuleAppliedAt(rule.getAppliedAt());
        dto.setRuleAgeDays(Math.max(0L, age.toDays()));
        dto.setRuleEndsAt(rule.getAppliedAt().plus(maxAge));
        return dto;
    }

    /**
     * Projects a soft-delete record onto the task-status DTO the {@code /deletion/tasks} list returns.
     * Statuses map onto the {@link DeletionStatus} vocabulary the UI knows, plus two of their own: waiting
     * states are {@code QUEUED}, a proven delete is {@code COMPLETED}, an applied delete without proof yet
     * is {@code IN_PROGRESS}, a refused delete is {@code REJECTED}, a failed one {@code FAILED}, a restore
     * {@code CANCELLED}. A dry run is {@value #STATUS_DRY_RUN}, never {@code COMPLETED}, and its error list
     * stays empty (the marker in {@code last_error} is for the would-delete list). A rule in force is
     * {@value #STATUS_RULE_IN_FORCE}: the rule fires, the bucket or container is not deleted.
     */
    static DeletionTaskStatusDTO softDeleteToStatusDto(SoftDeleteRecordEntity rec) {
        String status = mapSoftDeleteStatus(rec.getStatus());
        boolean completed = DeletionStatus.COMPLETED.name().equals(status);
        boolean failed = DeletionStatus.FAILED.name().equals(status) || DeletionStatus.REJECTED.name().equals(status)
                || STATUS_RESTORE_FAILED.equals(status);
        boolean ended = STATUS_RULE_ENDED.equals(status) || STATUS_RULE_ENDED_UNPROVEN.equals(status);
        boolean dryRun = STATUS_DRY_RUN.equals(status);
        boolean pending = DeletionStatus.QUEUED.name().equals(status) || DeletionStatus.IN_PROGRESS.name().equals(status);
        LocalDateTime terminalAt = toLocalDateTime(rec.getHardDeletedAt() != null ? rec.getHardDeletedAt()
                : rec.getRestoredAt() != null ? rec.getRestoredAt() : rec.getUpdatedAt());
        return DeletionTaskStatusDTO.builder()
                .taskId(rec.getId() != null ? rec.getId().toString() : null)
                .taskName(rec.getAssetUri() != null
                        ? rec.getAssetUri()
                        : (rec.getAssetId() != null ? rec.getAssetId().toString() : null))
                .status(status)
                .submittedAt(toLocalDateTime(rec.getQueuedAt()))
                .startedAt(toLocalDateTime(rec.getQueuedAt()))
                .completedAt(completed || failed || dryRun || ended ? terminalAt : null)
                .scheduledHardDeleteAt(rec.getScheduledHardDeleteAt())
                .triggeredBy(rec.getPolicyId() != null ? rec.getPolicyId() : rec.getRequestedBy())
                .justification(rec.getAuditNote())
                .totalAssetsInScope(1)
                .assetsProcessedSuccessfully(completed ? 1 : 0)
                .assetsFailedToProcess(failed ? 1 : 0)
                .assetsPendingProcessing(pending ? 1 : 0)
                .errorMessages(rec.getLastError() != null && !dryRun ? Collections.singletonList(rec.getLastError()) : null)
                .build();
    }

    static String mapSoftDeleteStatus(String softDeleteStatus) {
        if (softDeleteStatus == null) {
            return DeletionStatus.QUEUED.name();
        }
        return switch (softDeleteStatus) {
            case SoftDeleteRecordEntity.HARD_DELETED -> DeletionStatus.COMPLETED.name();
            case SoftDeleteRecordEntity.DRY_RUN_COMPLETE -> STATUS_DRY_RUN;
            case SoftDeleteRecordEntity.RULE_IN_FORCE -> STATUS_RULE_IN_FORCE;
            case SoftDeleteRecordEntity.RULE_ENDED -> STATUS_RULE_ENDED;
            case SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN -> STATUS_RULE_ENDED_UNPROVEN;
            case SoftDeleteRecordEntity.RESTORE_FAILED -> STATUS_RESTORE_FAILED;
            case SoftDeleteRecordEntity.AWAITING_PROOF -> DeletionStatus.IN_PROGRESS.name();
            case SoftDeleteRecordEntity.HARD_DELETE_FAILED -> DeletionStatus.FAILED.name();
            case SoftDeleteRecordEntity.HARD_DELETE_REFUSED -> DeletionStatus.REJECTED.name();
            case SoftDeleteRecordEntity.RESTORED -> DeletionStatus.CANCELLED.name();
            default -> DeletionStatus.QUEUED.name();
        };
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Override
    @Transactional(readOnly = true)
    public DeletionTaskStatusDTO approveTask(String taskId, TaskApprovalRequest approvalRequest) {
        return refuseLocalDecision(taskId, "approve");
    }

    @Override
    @Transactional(readOnly = true)
    public DeletionTaskStatusDTO rejectTask(String taskId, TaskApprovalRequest rejectionRequest) {
        return refuseLocalDecision(taskId, "reject");
    }

    private DeletionTaskStatusDTO refuseLocalDecision(String taskId, String operation) {
        DeletionTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DeletionTaskNotFoundException(taskId, operation));
        DeletionTaskStatusDTO dto = taskMapper.entityToStatusDTO(task);
        dto.setErrorMessages(Collections.singletonList(DECIDE_IN_POLICY_ENGINE));
        return dto;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
