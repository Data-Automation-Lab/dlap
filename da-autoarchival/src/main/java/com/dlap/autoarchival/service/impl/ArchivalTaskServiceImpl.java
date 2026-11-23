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

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.autoarchival.approval.ApprovalRefusedException;
import com.dlap.autoarchival.approval.ApprovalVerifier;
import com.dlap.autoarchival.approval.ArchiveApproval;
import com.dlap.autoarchival.approval.PolicyEngineApprovalClient;
import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.config.AsyncConfiguration;
import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.dto.ArchivalTaskListResponse;
import com.dlap.autoarchival.dto.ArchivalTaskRequest;
import com.dlap.autoarchival.dto.ArchivalTaskResponse;
import com.dlap.autoarchival.dto.ArchivalTaskStatusDTO;
import com.dlap.autoarchival.dto.RestoreRequestDTO;
import com.dlap.autoarchival.dto.RestoreResponseDTO;
import com.dlap.autoarchival.dto.TaskApprovalRequest;
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
import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.service.AssetLocation;
import com.dlap.autoarchival.service.AssetLocationResolver;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.autoarchival.service.IArchivalTaskService;
import com.dlap.autoarchival.service.storage.ICloudStorageService;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.autoarchival.service.storage.RestorationResult;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditOutcome;

import lombok.extern.slf4j.Slf4j;

/**
 * Archival tasks (RET-03). A task is the operator's view of one or more assets being archived; the
 * work itself is done by {@link ArchiveExecutor} on the provider's native primitive, one
 * {@code dlap_archive_rules} row per asset, and the task is derived from those rows
 * ({@link ArchiveTaskSync}).
 *
 * <p>Nothing here archives without an approval (docs/specs/approvals.md section 9):
 * <ul>
 *   <li>a policy fire must carry an executable {@code approval}; the executor re-reads it from the
 *       policy engine just before it acts;</li>
 *   <li>a task submitted through the REST API asks the policy engine for one approval per asset
 *       (naming the caller in {@code onBehalfOf}, so the caller cannot approve their own request)
 *       and runs when the {@code DECIDED} event arrives;</li>
 *   <li>the older single-administrator {@code /approve} call no longer starts anything.</li>
 * </ul>
 */
@Service("archivalTaskService") // Specify bean name
@Slf4j
public class ArchivalTaskServiceImpl implements IArchivalTaskService {

    /**
     * Reason recorded on an asset (and on the task, when no asset resolves) whose real
     * storage location could not be established. Fail-closed marker - the service used to
     * substitute {@code "s3://unknown/" + assetId} here, which guaranteed the archival
     * call could never touch the real object while still reporting progress.
     */
    static final String UNRESOLVED_LOCATION_MESSAGE =
            "Unresolved asset location: da-catalog has no assetUri/cloudProvider for this asset "
                    + "and the policy event carried no match snapshot. Refusing to archive an "
                    + "invented location.";

    /** Message returned by the retired single-administrator approval call. */
    static final String LOCAL_APPROVAL_RETIRED =
            "Archive approval moved to the policy engine approvals API (docs/specs/approvals.md section 9): "
                    + "decide the approval request named on each asset. This call no longer starts an archive.";

    private final ArchivalTaskRepository taskRepository;
    private final ArchivalTaskMapper taskMapper;
    private final IArchivalConfigService archivalConfigService; // JPA version
    private final List<ICloudStorageService> cloudStorageServices;
    private final AssetLocationResolver assetLocationResolver;
    private final ArchiveExecutor executor;
    private final ArchiveTaskSync taskSync;
    /** The asset status of a fired rule that was removed while its transitioned objects stay where they are. */
    static final String ASSET_RULE_REMOVED = "RULE_REMOVED";

    /**
     * The asset status of an asset whose GCS scope an identical live row already holds (Q-711): nothing was
     * planned, and that is not a refusal.
     */
    static final String ASSET_ALREADY_HELD = "ALREADY_HELD";

    /**
     * What a restore does for a native lifecycle rule (Q-605): it removes the rule, so no further objects move,
     * and it does not move objects the provider already transitioned back.
     */
    static final String RESTORE_NOTICE = "Restore process initiated. For a native lifecycle rule, restore removes "
            + "the rule so no further objects move; objects the provider already transitioned are not moved back. "
            + "Retrieve those with the provider's restore tooling.";

    private final ArchiveRuleRepository ruleRepository;
    private final ApprovalVerifier approvalVerifier;
    private final PolicyEngineApprovalClient approvalClient;
    private final ArchiveAudit audit;
    private final RulePlanner planner;
    private final Clock clock;

    /**
     * Provider used when one cannot be inferred from an asset's storage location. Configurable
     * so a single-cloud deployment is not silently pinned to a hardcoded {@code "AWS"} literal
     * (CLAUDE.md section 9). Per-asset routing now comes first: {@link AssetLocationResolver}
     * records the catalog's own {@code cloudProvider} on each asset status.
     */
    @org.springframework.beans.factory.annotation.Value("${dlap.autoarchival.default-provider:AWS}")
    private String defaultCloudProvider;

    /**
     * Self-reference used to invoke {@link Async} methods through the Spring
     * proxy so they actually run on the configured task executor. Injected
     * lazily to avoid a constructor cycle.
     */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private IArchivalTaskService self;

    /**
     * Creates the service.
     *
     * @param taskRepository        task rows
     * @param taskMapper            entity and DTO mapping
     * @param archivalConfigService global archival configuration
     * @param cloudStorageServices  direct storage services (restore of older tasks, single objects)
     * @param assetLocationResolver resolves each asset's storage location
     * @param executor              the native-rule executor
     * @param taskSync              derives a task from its rule rows
     * @param ruleRepository        rule rows
     * @param approvalVerifier      approval checks (section 9)
     * @param approvalClient        asks the policy engine for approvals
     * @param audit                 the audit log
     * @param planner               rule planner (provider lookup, validation)
     * @param clock                 the injected clock
     */
    public ArchivalTaskServiceImpl(ArchivalTaskRepository taskRepository,
                                   ArchivalTaskMapper taskMapper,
                                   IArchivalConfigService archivalConfigService,
                                   List<ICloudStorageService> cloudStorageServices,
                                   AssetLocationResolver assetLocationResolver,
                                   ArchiveExecutor executor,
                                   ArchiveTaskSync taskSync,
                                   ArchiveRuleRepository ruleRepository,
                                   ApprovalVerifier approvalVerifier,
                                   PolicyEngineApprovalClient approvalClient,
                                   ArchiveAudit audit,
                                   RulePlanner planner,
                                   Clock clock) {
        this.taskRepository = taskRepository;
        this.taskMapper = taskMapper;
        this.archivalConfigService = archivalConfigService;
        this.cloudStorageServices = cloudStorageServices;
        this.assetLocationResolver = assetLocationResolver;
        this.executor = executor;
        this.taskSync = taskSync;
        this.ruleRepository = ruleRepository;
        this.approvalVerifier = approvalVerifier;
        this.approvalClient = approvalClient;
        this.audit = audit;
        this.planner = planner;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ArchivalTaskResponse submitArchivalTask(ArchivalTaskRequest request) {
        return submitArchivalTask(request, null);
    }

    /**
     * What a submission produced.
     *
     * @param response   the response to the caller
     * @param planned    the approval ids of the assets that have a planned archive
     */
    private record Submitted(ArchivalTaskResponse response, List<String> planned) {
    }

    @Override
    @Transactional
    public ArchivalTaskResponse submitArchivalTask(ArchivalTaskRequest request, String requesterSub) {
        return submit(request, null, null, requesterSub).response();
    }

    /**
     * Creates an archival task, resolving each asset's real storage location first, planning its
     * native rule and recording the approval it runs under.
     *
     * @param request     the archival request
     * @param policyEvent the originating policy action event, or {@code null} for an
     *                    operator-submitted task. When present its {@code matchSnapshot}
     *                    is the preferred location source (no catalog round-trip).
     * @param approval    the approval the policy fire carries, or {@code null} for an operator request,
     *                    which asks the policy engine for one
     * @param requesterSub the person who submitted the request, or {@code null}
     * @return the task response; the task is FAILED when no asset could be located
     */
    private Submitted submit(ArchivalTaskRequest request, Map<String, Object> policyEvent,
                                        ArchiveApproval approval, String requesterSub) {
        log.info("Submitting new archival task: {}", request.getTaskName());
        if (request.getScope() == null || request.getScope().getAssetIds() == null || request.getScope().getAssetIds().isEmpty()) {
            log.warn("Archival task submission failed for task {}: Asset IDs are missing.", request.getTaskName());
            // Not creating an entity for a failed validation that doesn't hit DB
            return new Submitted(ArchivalTaskResponse.builder()
                    .taskId(null) // No ID generated
                    .taskName(request.getTaskName())
                    .status("FAILED_VALIDATION")
                    .submittedAt(now())
                    .message("Archival scope must contain at least one asset ID.")
                    .build(), List.of());
        }

        ArchivalTaskEntity entity = taskMapper.requestToEntity(request);
        entity.setTaskId(UUID.randomUUID().toString());
        entity.setSubmittedAt(now());
        if (requesterSub != null) {
            entity.setTriggeredBy(requesterSub);
        }
        // Every archive waits for an approval: a policy fire brings one, an operator request asks for one.
        entity.setStatus(approval == null ? ArchivalStatus.PENDING_APPROVAL : ArchivalStatus.SUBMITTED);

        Map<String, Object> parameters = parametersOf(request, policyEvent);
        String defaultTier = defaultTier();
        String fireId = policyEvent == null || policyEvent.get("fireId") == null
                ? null : String.valueOf(policyEvent.get("fireId"));

        // Each asset's REAL storage location is resolved up front (policy-event snapshot, else da-catalog).
        // An asset we cannot locate is marked FAILED here - it is never given an invented location.
        List<ArchivalTaskEntity.AssetArchivalStatusData> statuses = new ArrayList<>();
        List<String> planned = new ArrayList<>();
        int locatedCount = 0;
        for (String assetId : request.getScope().getAssetIds()) {
            Optional<AssetLocation> location = assetLocationResolver.resolveForPolicyAction(assetId, policyEvent);
            if (location.isEmpty()) {
                log.error("Cannot resolve a storage location for asset {} (task {}); marking it "
                        + "FAILED rather than archiving an invented location", assetId, entity.getTaskId());
                statuses.add(new ArchivalTaskEntity.AssetArchivalStatusData(
                        assetId, null, "FAILED", null, null, UNRESOLVED_LOCATION_MESSAGE, null, null));
                continue;
            }
            ArchivalTaskEntity.AssetArchivalStatusData status = new ArchivalTaskEntity.AssetArchivalStatusData(
                    assetId, null, "PENDING", location.get().uri(), location.get().cloudProvider(), null, null, null);
            try {
                String approvalId = approval != null ? approval.approvalId()
                        : askForApproval(entity, assetId, location.get(), parameters, defaultTier, requesterSub);
                ArchiveRuleEntity row = executor.prepare(new ArchiveExecutor.Request(entity.getTaskId(), assetId,
                        location.get().uri(), location.get().cloudProvider(), parameters, defaultTier, approvalId,
                        fireId));
                status.setApprovalId(approvalId);
                status.setRuleId(row.getRuleId());
                planned.add(approvalId);
                locatedCount++;
            } catch (DuplicateScopeException e) {
                // Q-711: an identical live row already holds this scope, so there is nothing to plan. Not a
                // refusal: one already-held audit record per holding row, nothing more on later fires.
                log.info("Archive of asset {} not planned: rule {} already holds the scope", assetId,
                        e.existingRuleId());
                status.setStatus(ASSET_ALREADY_HELD);
                status.setErrorMessage("Nothing to plan: " + e.getMessage());
                recordAlreadyHeld(assetId, location.get().uri(), e);
            } catch (RuleException e) {
                log.warn("Archive of asset {} refused at planning: {} {}", assetId, e.reason(), e.getMessage());
                status.setStatus("REFUSED");
                status.setErrorMessage(e.reason() + ": " + e.getMessage());
                recordRefusal(assetId, location.get().uri(), approval == null ? null : approval.approvalId(),
                        entity.getTaskId(), e.reason().name(), e.getMessage(), fireId);
            } catch (ApprovalRefusedException | IllegalStateException e) {
                log.warn("Archive of asset {} not started: {}", assetId, e.getMessage());
                status.setStatus("FAILED");
                status.setErrorMessage("approval could not be requested: " + e.getMessage());
            }
            statuses.add(status);
        }
        int heldCount = (int) statuses.stream().filter(a -> ASSET_ALREADY_HELD.equals(a.getStatus())).count();
        entity.setAssetStatuses(statuses);
        entity.setTotalAssetsInScope(statuses.size());
        entity.setAssetsPendingArchival(locatedCount);
        entity.setAssetsFailedToArchive(statuses.size() - locatedCount - heldCount);
        if (locatedCount == 0) {
            // Fail closed: nothing to archive for real, so never dispatch a cloud call.
            List<ArchivalTaskEntity.AssetArchivalStatusData> notHeld = statuses.stream()
                    .filter(a -> !ASSET_ALREADY_HELD.equals(a.getStatus())).toList();
            if (heldCount > 0 && notHeld.isEmpty()) {
                entity.setStatus(ArchivalStatus.ALREADY_HELD);
            } else {
                boolean allRefused = notHeld.stream().allMatch(a -> "REFUSED".equals(a.getStatus()));
                entity.setStatus(allRefused ? ArchivalStatus.REFUSED : ArchivalStatus.FAILED);
            }
            entity.setCompletedAt(now());
            entity.setErrorMessages(notHeld.stream().map(ArchivalTaskEntity.AssetArchivalStatusData::getErrorMessage)
                    .filter(m -> m != null).distinct().collect(Collectors.toList()));
            if (entity.getStatus() == ArchivalStatus.ALREADY_HELD && policyEvent != null) {
                // A recurring policy fires again and again for a scope that is already held: no new task row per
                // fire (Q-711). The one already-held audit record per holding row says what happened.
                log.info("Archive fire {} found every scope already held; no task is stored", fireId);
                return new Submitted(ArchivalTaskResponse.builder().taskId(null).taskName(request.getTaskName())
                        .status(ArchivalStatus.ALREADY_HELD.name()).submittedAt(now())
                        .message("Every scope is already held by an identical live archive rule; nothing to plan.")
                        .build(), List.of());
            }
        }

        ArchivalTaskEntity savedEntity = taskRepository.save(entity);
        log.info("Archival task {} submitted with ID {} and status {}.", savedEntity.getTaskName(),
                savedEntity.getTaskId(), savedEntity.getStatus());
        return new Submitted(taskMapper.entityToTaskResponse(savedEntity), List.copyOf(planned));
    }

    private String askForApproval(ArchivalTaskEntity task, String assetId, AssetLocation location,
                                  Map<String, Object> parameters, String defaultTier, String requesterSub) {
        // Validate first (tier, prefix, limits) so nobody is asked to approve a rule that cannot be written.
        planner.plan(location.cloudProvider(), location.uri(), parameters, defaultTier, null, 0);
        Map<String, Object> asked = new LinkedHashMap<>(parameters);
        asked.putIfAbsent("tier", defaultTier);
        return approvalClient.ask(assetId, location.uri(), location.cloudProvider(), "archive", asked,
                "Archive requested through task " + task.getTaskId(), requesterSub,
                "archive:" + task.getTaskId() + ":" + assetId);
    }

    private Map<String, Object> parametersOf(ArchivalTaskRequest request, Map<String, Object> policyEvent) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (policyEvent != null && policyEvent.get("actionParameters") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> p.put(String.valueOf(k), v));
        }
        ArchivalTaskRequest.ArchivalConfigOverride o = request.getOverrideConfig();
        if (o != null) {
            if (o.getTargetArchivalTier() != null) {
                p.put("tier", o.getTargetArchivalTier());
            }
            if (o.getPrefix() != null) {
                p.put("prefix", o.getPrefix());
            }
            if (o.getAfterDays() != null) {
                p.put("afterDays", o.getAfterDays());
            }
        }
        return p;
    }

    private String defaultTier() {
        try {
            ArchivalConfigDTO cfg = archivalConfigService.getArchivalConfig();
            return cfg == null || cfg.getDefaultArchivalTier() == null ? null : cfg.getDefaultArchivalTier();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ArchivalTaskStatusDTO getTaskStatus(String taskId) {
        log.debug("Fetching status for archival task ID: {}", taskId);
        return taskRepository.findById(taskId)
                .map(taskMapper::entityToStatusDTO)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public ArchivalTaskListResponse listTasks(Pageable pageable) {
        log.debug("Listing archival tasks with pagination: {}", pageable);
        Page<ArchivalTaskEntity> taskPage = taskRepository.findAll(pageable);
        List<ArchivalTaskStatusDTO> dtoList = taskPage.getContent().stream()
                .map(taskMapper::entityToStatusDTO)
                .collect(Collectors.toList());
        PageImpl<ArchivalTaskStatusDTO> dtoPage = new PageImpl<>(dtoList, pageable, taskPage.getTotalElements());
        return ArchivalTaskListResponse.fromPage(dtoPage);
    }

    @Override
    @Transactional
    public ArchivalTaskStatusDTO approveTask(String taskId, TaskApprovalRequest approvalRequest) {
        log.info("Local approval requested for archival task ID: {} (retired)", taskId);
        ArchivalTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ArchivalTaskNotFoundException(taskId, "approve"));
        // One administrator could approve and run an archive here, which dual control forbids
        // (docs/specs/approvals.md section 2). The call is kept so older clients get an answer, not a 404.
        ArchivalTaskStatusDTO current = taskMapper.entityToStatusDTO(task);
        current.setErrorMessages(Collections.singletonList(LOCAL_APPROVAL_RETIRED));
        return current;
    }

    @Override
    @Transactional
    public ArchivalTaskStatusDTO rejectTask(String taskId, TaskApprovalRequest rejectionRequest, String rejectedBy) {
        log.info("Rejecting archival task ID: {}", taskId);
        ArchivalTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ArchivalTaskNotFoundException(taskId, "reject"));

        if (task.getStatus() != ArchivalStatus.PENDING_APPROVAL) {
            log.warn("Cannot reject task {}: not in PENDING_APPROVAL state (current: {}).", taskId, task.getStatus());
            ArchivalTaskStatusDTO currentStatusDto = taskMapper.entityToStatusDTO(task);
            currentStatusDto.setErrorMessages(Collections.singletonList("Task is not in a state that can be rejected."));
            return currentStatusDto;
        }
        // A rejected task must never run if its approval is granted later.
        for (ArchiveRuleEntity row : ruleRepository.findByTaskId(taskId)) {
            if (ArchiveRuleEntity.STATUS_PLANNED.equals(row.getStatus())) {
                if (ruleRepository.endIfPlanned(row.getRuleId(), ArchiveRuleEntity.STATUS_REFUSED,
                        "TASK_REJECTED: the task was rejected by an operator", clock.instant()) != 1) {
                    continue; // a worker claimed the row first (Q-609); the approval it holds stands
                }
                // The approval can still be granted later; the chain must say why that approved archive never ran.
                recordRefusal(row.getAssetId(), row.getAssetUri(), row.getApprovalId(), taskId, "TASK_REJECTED",
                        "the task was rejected by an operator before the approval was used",
                        row.getApprovalId() + ":task-rejected", restoreActor(rejectedBy));
            }
        }
        task.setStatus(ArchivalStatus.REJECTED);
        if (rejectionRequest != null) {
            task.setRejectionComments(rejectionRequest.getComments());
        }
        ArchivalTaskEntity savedTask = taskRepository.save(task);
        return taskMapper.entityToStatusDTO(savedTask);
    }

    @Override
    @Transactional
    public RestoreResponseDTO requestRestore(String taskId, RestoreRequestDTO restoreRequestDto) {
        log.info("Requesting restore for archival task ID: {}", taskId);
        ArchivalTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ArchivalTaskNotFoundException(taskId, "restore request"));

        // Restorable: the legacy COMPLETED, and the RET-03 states where a rule is in place or a transition was seen.
        if (task.getStatus() != ArchivalStatus.COMPLETED && task.getStatus() != ArchivalStatus.APPROVED
                && task.getStatus() != ArchivalStatus.EXECUTED && task.getStatus() != ArchivalStatus.RULE_APPLIED
                && task.getStatus() != ArchivalStatus.RULE_RENDERED
                && task.getStatus() != ArchivalStatus.PARTIALLY_COMPLETED) {
            log.warn("Cannot restore task {}: not in a restorable state (current: {}).", taskId, task.getStatus());
            return RestoreResponseDTO.builder()
                    .archivalTaskId(taskId)
                    .status("NOT_RESTORABLE")
                    .requestedAt(now())
                    .message("Archival task is not in a state that allows restoration.")
                    .build();
        }

        // Intent first: if the audit log cannot record the restore, it does not start.
        String tier = restoreRequestDto == null ? null : restoreRequestDto.getRestoreTier();
        AuditActor actor = restoreActor(restoreRequestDto == null ? null : restoreRequestDto.getRequestedBy());
        try {
            for (ArchivalTaskEntity.AssetArchivalStatusData s : assetStatusesOf(task)) {
                if (s.getArchivalLocation() != null) {
                    audit.restore("requested", taskId, s.getArchivalLocation(), AuditOutcome.REQUESTED, actor, tier);
                }
            }
        } catch (AuditException | IllegalArgumentException e) {
            log.error("Restore of task {} not started: the audit log refused the intent record: {}", taskId, e.getMessage());
            return RestoreResponseDTO.builder()
                    .archivalTaskId(taskId)
                    .status("FAILED")
                    .requestedAt(now())
                    .message("The audit log is unavailable; nothing was changed.")
                    .build();
        }

        task.setRestoreRequestDetails(taskMapper.restoreRequestDtoToData(restoreRequestDto));
        task.getRestoreRequestDetails().setRequestedAt(now()); // Ensure request time is set
        task.getRestoreRequestDetails().setTriggeredBy(restoreRequestDto == null ? null : restoreRequestDto.getRequestedBy());
        task.setLastRestoreRequestedAt(now());
        task.setLastRestoreStatus(ArchivalStatus.RESTORATION_REQUESTED.name());
        task.setStatus(ArchivalStatus.RESTORATION_REQUESTED); // Update main task status

        ArchivalTaskEntity savedTask = taskRepository.save(task);
        log.info("Restore initiated for task {}. New status: {}. Restore Tier: {}",
                 savedTask.getTaskId(), savedTask.getStatus(), tier);

        // Dispatch the restoration work asynchronously so it can call cloud APIs
        // per asset and update the task as each one progresses.
        self.processRestorationAsync(savedTask.getTaskId());

        return RestoreResponseDTO.builder()
                .restoreTaskId(savedTask.getTaskId()) // Using archival task ID as restore task ID for now
                .archivalTaskId(savedTask.getTaskId())
                .status(savedTask.getStatus().name())
                .requestedAt(savedTask.getLastRestoreRequestedAt())
                .message(RESTORE_NOTICE)
                .build();
    }

    @Override
    public void processArchivalAction(Map<String, Object> event) {
        // Extract relevant information from the event
        String eventId = event.get("eventId") == null ? null : String.valueOf(event.get("eventId"));
        String policyId = event.get("policyId") == null ? null : String.valueOf(event.get("policyId"));
        String assetId = event.get("assetId") == null ? null : String.valueOf(event.get("assetId"));
        String fireId = event.get("fireId") == null ? null : String.valueOf(event.get("fireId"));

        log.info("Processing archival action for event: {}, policy: {}, asset: {}", eventId, policyId, assetId);

        // A missing asset id is a permanent, non-retryable data defect - a Kafka
        // retry cannot supply one. Fail fast with a permanent exception so the
        // listener's error handler routes it to the DLQ instead of NPE-ing on
        // List.of(null) or silently creating an empty-scope task.
        if (assetId == null || assetId.isBlank()) {
            log.error("PolicyActionEvent {} (policy {}) has no assetId - cannot create archival task", eventId, policyId);
            throw new PermanentArchivalException("PolicyActionEvent " + eventId + " is missing a target assetId");
        }

        // Approvals.md section 9, rule 1: no executable approval, no archive. The record is acknowledged
        // by the listener and the refusal goes to the audit log.
        ArchiveApproval approval;
        try {
            approval = approvalVerifier.fromEvent(event);
        } catch (ApprovalRefusedException e) {
            log.warn("Archive of asset {} refused: {} ({})", assetId, e.reason(), e.getMessage());
            recordRefusal(assetId, snapshotUri(event), null, null, e.reason().name(), e.getMessage(),
                    fireId != null ? fireId : eventId);
            return;
        }

        try {
            // Rule 5: a repeated approval or fire is the same decision. A row still PLANNED is resumed.
            Optional<ArchiveRuleEntity> known = ruleRepository.findByApprovalId(approval.approvalId());
            if (known.isEmpty() && fireId != null) {
                known = ruleRepository.findByFireId(fireId);
            }
            if (known.isPresent()) {
                log.info("Approval {} was already seen (rule {}, {}); not creating a second task",
                        approval.approvalId(), known.get().getRuleId(), known.get().getStatus());
                if (ArchiveRuleEntity.STATUS_PLANNED.equals(known.get().getStatus())) {
                    runPlanned(known.get().getApprovalId(), known.get().getTaskId(), AuditActor.service(ArchiveAudit.ACTOR_SERVICE));
                }
                return;
            }

            ArchivalTaskRequest request = ArchivalTaskRequest.builder()
                    .taskName("Auto-archival from policy " + policyId)
                    .scope(ArchivalTaskRequest.ArchivalScope.builder().assetIds(List.of(assetId)).build())
                    .justification("Automated archival triggered by policy engine")
                    .build();

            // The event is passed through so its matchSnapshot (assetUri + cloudProvider) can locate the asset
            // without a catalog round-trip; da-catalog is the fallback, and an unlocatable asset fails the task closed.
            Submitted submitted = submit(request, event, approval, null);
            ArchivalTaskResponse response = submitted.response();
            log.info("Archival task {} for event {}: {}", response.getTaskId(), eventId, response.getStatus());
            for (String approvalId : submitted.planned()) {
                runPlanned(approvalId, response.getTaskId(), AuditActor.service(ArchiveAudit.ACTOR_SERVICE));
            }
        } catch (RuntimeException e) {
            // Propagate transient failures raised from downstream calls unchanged.
            if (e instanceof TransientArchivalException || e instanceof PermanentArchivalException) {
                throw e;
            }
            // IOException subclasses (including SocketTimeoutException) surfaced
            // through a RuntimeException wrapper are treated as transient. The
            // Kafka listener's DefaultErrorHandler will retry with exponential
            // backoff before routing to the DLQ.
            if (containsIoCause(e)) {
                log.warn("Transient failure processing archival action for event {}: {}", eventId, e.getMessage(), e);
                throw new TransientArchivalException("Transient failure processing archival action for event " + eventId, e);
            }
            log.error("Permanent failure processing archival action for event {}: {}", eventId, e.getMessage(), e);
            throw new PermanentArchivalException("Permanent failure processing archival action for event " + eventId, e);
        }
    }

    /**
     * Runs a planned action in the calling thread and derives the task. A transient failure to check the
     * approval is rethrown so the Kafka container retries the record with backoff.
     */
    private void runPlanned(String approvalId, String taskId, AuditActor actor) {
        try {
            executor.execute(approvalId, actor);
        } catch (ApprovalRefusedException e) {
            if (e.isTransient()) {
                taskSync.sync(taskId);
                throw new TransientArchivalException("The approval could not be checked: " + e.getMessage(), e);
            }
            throw new PermanentArchivalException("The approval was refused: " + e.getMessage(), e);
        }
        taskSync.sync(taskId);
    }

    @Override
    public void logDryRun(Map<String, Object> event) {
        try {
            String assetId = event.get("assetId") == null ? null : String.valueOf(event.get("assetId"));
            if (assetId == null || assetId.isBlank()) {
                return;
            }
            Optional<AssetLocation> location = assetLocationResolver.resolveForPolicyAction(assetId, event);
            if (location.isEmpty()) {
                log.info("DRY-RUN archive of asset {}: no storage location resolves", assetId);
                return;
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            if (event.get("actionParameters") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> parameters.put(String.valueOf(k), v));
            }
            var plan = planner.plan(location.get().cloudProvider(), location.get().uri(), parameters, defaultTier(),
                    null, null);
            log.info("DRY-RUN archive of asset {}: {} rule {} to {} after {} days; {} object(s) listed{}; {}",
                    assetId, plan.scopeKind(), plan.spec().ruleId(), plan.spec().nativeClass(), plan.spec().afterDays(),
                    plan.listing().entries().size(), plan.listing().truncated() ? " (more match)" : "", plan.notes());
        } catch (RuleException e) {
            log.info("DRY-RUN archive would be refused: {} {}", e.reason(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("DRY-RUN archive could not be planned: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void onApprovalDecided(String approvalId, boolean executable, String status) {
        Optional<ArchiveRuleEntity> found = ruleRepository.findByApprovalId(approvalId);
        if (found.isEmpty() || !ArchiveRuleEntity.STATUS_PLANNED.equals(found.get().getStatus())) {
            return; // not ours, or already decided
        }
        ArchiveRuleEntity row = found.get();
        if (executable) {
            self.executeApprovalAsync(approvalId);
            return;
        }
        if (ruleRepository.endIfPlanned(row.getRuleId(), ArchiveRuleEntity.STATUS_REFUSED,
                ArchiveRuleEntity.clip("APPROVAL_" + status + ": the approval is not executable"),
                clock.instant()) != 1) {
            return; // a worker claimed the row first (Q-609); it is theirs
        }
        recordRefusal(row.getAssetId(), row.getAssetUri(), approvalId, row.getTaskId(), "APPROVAL_" + status,
                "the approval ended " + status + " and the archive never ran", row.getFireId());
        taskSync.sync(row.getTaskId());
    }

    @Override
    @Async(AsyncConfiguration.ARCHIVAL_TASK_EXECUTOR)
    public void executeApprovalAsync(String approvalId) {
        ruleRepository.findByApprovalId(approvalId).ifPresent(row -> {
            try {
                executor.execute(approvalId, taskActor(row.getTaskId()));
            } catch (ApprovalRefusedException e) {
                log.warn("Archive under approval {} not run: {}", approvalId, e.getMessage());
            } catch (RuntimeException e) {
                log.error("Archive under approval {} failed unexpectedly: {}", approvalId, e.getMessage(), e);
            }
            taskSync.sync(row.getTaskId());
        });
    }

    private AuditActor taskActor(String taskId) {
        return taskRepository.findById(taskId).map(ArchivalTaskEntity::getTriggeredBy)
                .filter(s -> s != null && !s.isBlank()).map(AuditActor::user)
                .orElseGet(() -> AuditActor.service(ArchiveAudit.ACTOR_SERVICE));
    }

    private AuditActor restoreActor(String requestedBy) {
        return requestedBy == null || requestedBy.isBlank() ? AuditActor.service(ArchiveAudit.ACTOR_SERVICE)
                : AuditActor.user(requestedBy);
    }

    private void recordRefusal(String assetId, String assetUri, String approvalId, String taskId, String reason,
                               String detail, String key) {
        recordRefusal(assetId, assetUri, approvalId, taskId, reason, detail, key, null);
    }

    /**
     * Writes the one already-held audit record for a holding row and asset (Q-711). A failure is logged and never
     * undone: nothing was changed, so there is no effect to reconcile.
     */
    private void recordAlreadyHeld(String assetId, String assetUri, DuplicateScopeException held) {
        try {
            ruleRepository.findById(held.existingRuleId()).ifPresentOrElse(
                    holding -> audit.alreadyHeld(assetUri, assetId, holding),
                    () -> log.warn("Rule {} that held the scope is gone; no already-held record is written",
                            held.existingRuleId()));
        } catch (AuditException | IllegalArgumentException e) {
            log.error("An already-held record could not be written to the audit log: {}", e.getMessage());
        }
    }

    private void recordRefusal(String assetId, String assetUri, String approvalId, String taskId, String reason,
                               String detail, String key, AuditActor actor) {
        try {
            String k = key != null ? key : (approvalId != null ? approvalId : assetId);
            String target = assetUri != null ? assetUri : assetId;
            if (actor == null) {
                audit.refused(k, target, reason, detail, approvalId, taskId);
            } else {
                audit.refused(k, target, reason, detail, approvalId, taskId, actor);
            }
        } catch (AuditException | IllegalArgumentException e) {
            log.error("A refusal could not be written to the audit log ({}): {}", reason, e.getMessage());
        }
    }

    private static String snapshotUri(Map<String, Object> event) {
        if (event.get("matchSnapshot") instanceof Map<?, ?> m && m.get("assetUri") != null) {
            return String.valueOf(m.get("assetUri"));
        }
        return event.get("assetUri") == null ? null : String.valueOf(event.get("assetUri"));
    }

    private static boolean containsIoCause(Throwable t) {
        Throwable cursor = t;
        int hops = 0;
        while (cursor != null && hops < 8) {
            if (cursor instanceof IOException) {
                return true;
            }
            cursor = cursor.getCause();
            hops++;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Async execution of archival and restoration work
    // ------------------------------------------------------------------

    @Override
    @Async(AsyncConfiguration.ARCHIVAL_TASK_EXECUTOR)
    public void processArchivalTaskAsync(String taskId) {
        log.info("[async] Starting archival execution for task {}", taskId);
        ArchivalTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("[async] Archival task {} not found - skipping", taskId);
            return;
        }
        AuditActor actor = taskActor(taskId);
        for (ArchiveRuleEntity row : ruleRepository.findByTaskId(taskId)) {
            if (!ArchiveRuleEntity.STATUS_PLANNED.equals(row.getStatus())) {
                continue;
            }
            try {
                executor.execute(row.getApprovalId(), actor);
            } catch (ApprovalRefusedException e) {
                log.warn("[async] Archive under approval {} not run: {}", row.getApprovalId(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("[async] Archive under approval {} failed unexpectedly: {}", row.getApprovalId(),
                        e.getMessage(), e);
            }
        }
        taskSync.sync(taskId);
        log.info("[async] Archival task {} processed", taskId);
    }

    @Override
    @Async(AsyncConfiguration.ARCHIVAL_TASK_EXECUTOR)
    public void processRestorationAsync(String taskId) {
        log.info("[async] Starting restoration execution for task {}", taskId);
        ArchivalTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("[async] Archival task {} not found for restoration - skipping", taskId);
            return;
        }

        task.setStatus(ArchivalStatus.RESTORATION_IN_PROGRESS);
        taskRepository.save(task);

        String restoreTier = task.getRestoreRequestDetails() != null && task.getRestoreRequestDetails().getRestoreTier() != null
                ? task.getRestoreRequestDetails().getRestoreTier()
                : "STANDARD";
        AuditActor actor = restoreActor(task.getRestoreRequestDetails() == null ? null
                : task.getRestoreRequestDetails().getTriggeredBy());
        Map<String, ArchiveRuleEntity> byAsset = ruleRepository.findByTaskId(taskId).stream()
                .collect(Collectors.toMap(ArchiveRuleEntity::getAssetId, Function.identity(), (a, b) -> a));

        int successCount = 0;
        int ruleRemovedCount = 0;
        int failCount = 0;
        List<ArchivalTaskEntity.AssetArchivalStatusData> assetStatuses = assetStatusesOf(task);

        for (ArchivalTaskEntity.AssetArchivalStatusData assetStatus : assetStatuses) {
            String location = assetStatus.getArchivalLocation();
            if (location == null) {
                assetStatus.setStatus("FAILED");
                assetStatus.setErrorMessage("Missing archival location");
                failCount++;
                continue;
            }
            ArchiveRuleEntity row = byAsset.get(assetStatus.getAssetId());
            String error;
            if (row != null && ArchiveRuleEntity.MODE_NATIVE_RULE.equals(row.getMode())) {
                error = undoRule(row, assetStatus);
            } else {
                error = restoreObject(task, assetStatus, row, restoreTier);
            }
            // Q-670: RULE_REMOVED is a normal end state (the rule is gone, the moved objects stay where they are),
            // so it is never overwritten with RESTORATION_FAILED. The message stays on the asset.
            boolean ruleRemoved = error != null && ASSET_RULE_REMOVED.equals(assetStatus.getStatus());
            if (error == null) {
                successCount++;
            } else if (ruleRemoved) {
                assetStatus.setErrorMessage(error);
                ruleRemovedCount++;
            } else {
                assetStatus.setErrorMessage(error);
                if (!"RESTORATION_IN_PROGRESS".equals(assetStatus.getStatus())) {
                    assetStatus.setStatus("RESTORATION_FAILED");
                }
                failCount++;
            }
            try {
                String step = error == null ? "restored" : ruleRemoved ? "rule_removed" : "restore_failed";
                audit.restore(step, taskId, location,
                        error == null || ruleRemoved ? AuditOutcome.SUCCEEDED : AuditOutcome.FAILED, actor,
                        restoreTier);
            } catch (AuditException | IllegalArgumentException e) {
                log.error("[async] Restore of {} could not be written to the audit log: {}", taskId, e.getMessage());
            }
        }

        task.setAssetStatuses(assetStatuses);
        int normalEnd = successCount + ruleRemovedCount;
        if (failCount == 0 && successCount == 0 && ruleRemovedCount > 0) {
            // Q-708: every asset ended RULE_REMOVED, so no object was restored. RESTORED would say otherwise.
            task.setStatus(ArchivalStatus.RULE_REMOVED);
            task.setLastRestoreStatus(ArchivalStatus.RULE_REMOVED.name());
        } else if (failCount == 0 && normalEnd > 0) {
            task.setStatus(ArchivalStatus.RESTORED);
            task.setLastRestoreStatus(ArchivalStatus.RESTORED.name());
        } else if (normalEnd > 0) {
            task.setStatus(ArchivalStatus.PARTIALLY_COMPLETED);
            task.setLastRestoreStatus(ArchivalStatus.PARTIALLY_COMPLETED.name());
        } else {
            task.setStatus(ArchivalStatus.RESTORATION_FAILED);
            task.setLastRestoreStatus(ArchivalStatus.RESTORATION_FAILED.name());
        }
        taskRepository.save(task);
        log.info("[async] Restoration task {} finished with status {} (success={}, ruleRemoved={}, failed={})",
                taskId, task.getStatus(), successCount, ruleRemovedCount, failCount);
    }

    /**
     * Takes a native rule out. A rule that has not fired is simply removed. Once objects have moved the
     * rule is still removed (no further objects move) but DLAP does not restore them: that is a bulk
     * retrieval the provider runs, and the asset says so rather than claiming a restore.
     *
     * @return null when the asset is restored, else the reason it is not
     */
    private String undoRule(ArchiveRuleEntity row, ArchivalTaskEntity.AssetArchivalStatusData assetStatus) {
        try {
            if (ArchiveRuleEntity.STATUS_EXECUTING.equals(row.getStatus())) {
                // Q-674: a worker holds the row and may be writing the rule right now. Marking it REMOVED would be
                // overwritten by that worker's APPLIED, and the restore would report success on a live rule.
                return "The archive is still being applied (EXECUTING). Wait for it to finish, or ask an "
                        + "administrator to reset it, then restore again. Nothing was changed.";
            }
            NativeRuleProvider provider = planner.providerFor(row.getProvider());
            boolean keptCustomerRule = row.isAlreadyPresent();
            if (ArchiveRuleEntity.STATUS_APPLIED.equals(row.getStatus())
                    || ArchiveRuleEntity.STATUS_EXECUTED.equals(row.getStatus())) {
                if (keptCustomerRule) {
                    // Q-608: the provider held this rule before DLAP applied it, so it may be the customer's own
                    // (GCS rules have no id). A restore leaves it where it is.
                    log.info("Restore of rule {} leaves the provider rule in place: it was already there when DLAP "
                            + "applied it", row.getRuleId());
                } else if (!provider.isMock()) {
                    provider.remove(ArchiveExecutor.specOf(row));
                }
            }
            String removedFrom = markRemoved(row);
            if (removedFrom == null) {
                return "The archive changed while the restore ran (it is now "
                        + ruleRowStatus(row.getRuleId()) + "). Nothing was marked removed; restore again.";
            }
            boolean wasExecuted = ArchiveRuleEntity.STATUS_EXECUTED.equals(removedFrom);
            try {
                audit.removed(row, AuditActor.service(ArchiveAudit.ACTOR_SERVICE),
                        Map.of("providerRuleKept", keptCustomerRule));
            } catch (AuditException | IllegalArgumentException e) {
                log.error("Rule removal {} could not be written to the audit log: {}", row.getRuleId(), e.getMessage());
            }
            if (wasExecuted) {
                assetStatus.setStatus(ASSET_RULE_REMOVED);
                return keptCustomerRule
                        ? "DLAP stopped managing this rule. The identical rule was already in place before DLAP "
                        + "applied it, so it was left at the provider. Objects that already moved are not "
                        + "restored by DLAP; retrieve them with the provider's restore tooling."
                        : "The rule was removed and no more objects will move. Objects that already moved are not "
                        + "restored by DLAP; retrieve them with the provider's restore tooling.";
            }
            assetStatus.setStatus("RESTORED");
            assetStatus.setRestoredAt(now());
            return null;
        } catch (RuleException e) {
            return e.reason() + ": " + e.getMessage();
        } catch (RuntimeException e) {
            return "UNEXPECTED: " + e.getClass().getSimpleName();
        }
    }

    /**
     * Marks a row {@code REMOVED} with a conditional update on the status the caller saw (Q-674). A proof update
     * that moved the row from {@code APPLIED} to {@code EXECUTED} between the load and now is followed once: the
     * provider rule was already dealt with from the earlier status, and the row then ends {@code REMOVED} from the
     * status it really had. Any other change (a claim, a failure, another restore) is not overwritten.
     *
     * @param row the loaded row; its in-memory status is updated on success
     * @return the status the row had when it became {@code REMOVED}, or {@code null} when it changed to a status a
     *         restore must not overwrite
     */
    private String markRemoved(ArchiveRuleEntity row) {
        String expected = row.getStatus();
        for (int attempt = 0; attempt < 3; attempt++) {
            Instant now = clock.instant();
            if (ruleRepository.markRemovedIf(row.getRuleId(), expected, now) == 1) {
                row.markRemoved(now);
                return expected;
            }
            Optional<ArchiveRuleEntity> current = ruleRepository.findById(row.getRuleId());
            if (current.isEmpty()) {
                return null;
            }
            String seen = current.get().getStatus();
            if (!ArchiveRuleEntity.STATUS_EXECUTED.equals(seen) || ArchiveRuleEntity.STATUS_EXECUTED.equals(expected)) {
                return null;
            }
            expected = seen;
        }
        return null;
    }

    private String ruleRowStatus(String ruleId) {
        return ruleRepository.findById(ruleId).map(ArchiveRuleEntity::getStatus).orElse("gone");
    }

    /** The older per-object restore, for a single object archived with a direct change and for tasks from before RET-03. */
    private String restoreObject(ArchivalTaskEntity task, ArchivalTaskEntity.AssetArchivalStatusData assetStatus,
                                 ArchiveRuleEntity row, String restoreTier) {
        ICloudStorageService storageService = resolveCloudStorageService(task);
        if (storageService == null) {
            return "No ICloudStorageService available for the task";
        }
        UUID assetUuid = safeToUuid(assetStatus.getAssetId());
        try {
            RestorationResult result = storageService.restoreData(assetUuid, assetStatus.getArchivalLocation(), restoreTier);
            if (result != null && (result.getStatus() == OperationStatus.COMPLETED
                    || result.getStatus() == OperationStatus.IN_PROGRESS)) {
                assetStatus.setStatus(result.getStatus() == OperationStatus.COMPLETED ? "RESTORED" : "RESTORATION_IN_PROGRESS");
                assetStatus.setRestoredAt(now());
                if (row != null && markRemoved(row) == null) {
                    log.warn("Rule {} changed while its object was restored; the row was not marked removed",
                            row.getRuleId());
                }
                return null;
            }
            return result != null ? result.getErrorMessage() : "Unknown failure";
        } catch (RuntimeException e) {
            log.error("[async] Failed to restore asset {} for task {}: {}", assetStatus.getAssetId(), task.getTaskId(),
                    e.getMessage(), e);
            return e.getMessage();
        }
    }

    private List<ArchivalTaskEntity.AssetArchivalStatusData> assetStatusesOf(ArchivalTaskEntity task) {
        return task.getAssetStatuses() != null ? task.getAssetStatuses() : Collections.emptyList();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Selects the registered {@link ICloudStorageService} that supports the task's target
     * cloud provider. <b>Fails closed</b> (choke-point #4): if no registered service supports
     * the provider, returns {@code null} rather than defaulting to the first one - so a
     * {@code gs://} or {@code azure://} asset is never silently archived to AWS. The caller
     * marks the task FAILED. The provider is inferred by {@link #inferCloudProvider}.
     */
    private ICloudStorageService resolveCloudStorageService(ArchivalTaskEntity task) {
        String provider = inferCloudProvider(task);
        if (cloudStorageServices == null || cloudStorageServices.isEmpty()) {
            return null;
        }
        for (ICloudStorageService candidate : cloudStorageServices) {
            if (candidate.supportsCloudProvider(provider)) {
                return candidate;
            }
        }
        log.error("No ICloudStorageService supports provider '{}' for archival task {}; failing "
                + "closed (refusing to act on a different cloud). Register a storage service "
                + "for '{}' to enable archival.", provider, task.getTaskId(), provider);
        return null;
    }

    private String inferCloudProvider(ArchivalTaskEntity task) {
        // Prefer the provider resolved alongside the asset's real location (da-catalog's own
        // cloudProvider, or the policy event's match snapshot). Next best is the storage
        // location's URI scheme (e.g. a restore of a gs://-archived asset routes to GCP).
        // Only then the configured default.
        if (task != null && task.getAssetStatuses() != null) {
            for (ArchivalTaskEntity.AssetArchivalStatusData s : task.getAssetStatuses()) {
                if (s != null && s.getCloudProvider() != null && !s.getCloudProvider().isBlank()) {
                    return s.getCloudProvider().trim().toUpperCase(java.util.Locale.ROOT);
                }
            }
            for (ArchivalTaskEntity.AssetArchivalStatusData s : task.getAssetStatuses()) {
                String p = providerFromLocation(s == null ? null : s.getArchivalLocation());
                if (p != null) {
                    return p;
                }
            }
        }
        return (defaultCloudProvider == null || defaultCloudProvider.isBlank())
                ? "AWS" : defaultCloudProvider.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Maps a storage-location URI scheme to a cloud provider, or {@code null} if unknown.
     * Package-private for testing and for future per-asset routing.
     */
    static String providerFromLocation(String location) {
        if (location == null) {
            return null;
        }
        String l = location.trim().toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("s3://") || l.startsWith("arn:aws:")) {
            return "AWS";
        }
        if (l.startsWith("gs://")) {
            return "GCP";
        }
        if (l.startsWith("az://") || l.startsWith("abfss://") || l.contains(".blob.core.windows.net")) {
            return "AZURE";
        }
        return null;
    }

    private UUID safeToUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(raw.getBytes());
        }
    }
}
