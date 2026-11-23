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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.dto.ApprovalViewDTO;
import com.dlap.autodelete.model.DeletionStatus;
import com.dlap.autodelete.model.DeletionTaskEntity;
import com.dlap.autodelete.model.DeletionTaskEntity.AssetDeletionStatusData;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves the assets of a task submitted through {@code POST /api/v1/deletion/tasks} forward once their
 * approvals are decided (approvals.md section 9, rule 4: an executor that asked acts only when the
 * request is executable). Each executable asset goes through {@link SoftDeleteAdmission} with the
 * submitter excluded from the approvers; a pending approval is read again later.
 *
 * <p>Per-asset statuses: {@code AWAITING_APPROVAL}, {@code SOFT_DELETED} (admitted into its window),
 * {@code REFUSED} (a rule refused it; audited), {@code FAILED} (the approval was never recorded).
 * The task ends {@code COMPLETED} when every asset was admitted, {@code PARTIALLY_COMPLETED} when
 * some were, {@code REJECTED} when every approval was rejected, and {@code FAILED} otherwise.
 */
@Component
public class TaskApprovalProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(TaskApprovalProcessor.class);

    /** The asset waits for its approval. */
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    /** The asset is in its soft-delete window. */
    public static final String SOFT_DELETED = "SOFT_DELETED";
    /** A rule refused the asset. */
    public static final String REFUSED = "REFUSED";
    /** The approval could not be requested. */
    public static final String FAILED = "FAILED";

    private final DeletionTaskRepository tasks;
    private final ApprovalClient approvals;
    private final SoftDeleteAdmission admission;
    private final RetireAuditLog audit;
    private final RetireProperties properties;
    private final Clock clock;

    /**
     * Creates the processor.
     *
     * @param tasks      the tasks
     * @param approvals  the approval API
     * @param admission  the admission
     * @param audit      the audit log
     * @param properties the settings
     * @param clock      the clock
     */
    public TaskApprovalProcessor(DeletionTaskRepository tasks, ApprovalClient approvals, SoftDeleteAdmission admission,
            RetireAuditLog audit, RetireProperties properties, Clock clock) {
        this.tasks = tasks;
        this.approvals = approvals;
        this.admission = admission;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Handles one claimed task, inside the caller's transaction.
     *
     * @param task the task, locked
     */
    public void process(DeletionTaskEntity task) {
        Instant now = clock.instant();
        List<AssetDeletionStatusData> rows = new ArrayList<>();
        boolean waiting = false;
        for (AssetDeletionStatusData original : task.getAssetStatuses() == null
                ? List.<AssetDeletionStatusData>of() : task.getAssetStatuses()) {
            AssetDeletionStatusData row = copy(original);
            rows.add(row);
            if (!AWAITING_APPROVAL.equals(row.getStatus())) {
                continue;
            }
            UUID approvalId = ApprovalEvidence.uuid(row.getApprovalId());
            UUID assetId = ApprovalEvidence.uuid(row.getAssetId());
            if (approvalId == null || assetId == null) {
                finish(row, REFUSED, RefusalReason.INVALID.code(), now);
                // Every refusal is a record (Q-632).
                audit.refused(now, RetireAuditLog.SERVICE, RetireAuditLog.safeTarget(row.getAssetId()),
                        task.getTaskId(), "task", RefusalReason.INVALID,
                        "the task row has no valid " + (assetId == null ? "asset id" : "approval id"),
                        Map.of("taskId", String.valueOf(task.getTaskId())));
                continue;
            }
            Optional<ApprovalViewDTO> view;
            try {
                view = approvals.read(approvalId);
            } catch (RetireRemoteException e) {
                if (e.isRetryable()) {
                    waiting = true;
                    continue;
                }
                view = Optional.empty();
            }
            if (view.isPresent() && "PENDING".equalsIgnoreCase(view.get().status())) {
                waiting = true;
                continue;
            }
            boolean hard = "HARD".equalsIgnoreCase(row.getDeletionType());
            Integer window = task.getOverrideConfig() == null || task.getOverrideConfig().getSoftDeleteRetentionDays() == null
                    ? null : Math.toIntExact(task.getOverrideConfig().getSoftDeleteRetentionDays());
            SoftDeleteAdmission.Outcome outcome;
            try {
                outcome = admission.admitTaskAsset(task.getTaskId(), assetId, approvalId, task.getRequestedBy(), window,
                        hard, task.getJustification());
            } catch (RetireRemoteException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                waiting = true;
                continue;
            }
            if (outcome.admitted()) {
                row.setSoftDeleteRecordId(outcome.recordId().toString());
                finish(row, SOFT_DELETED, null, now);
            } else {
                finish(row, REFUSED, outcome.reason().code(), now);
            }
        }
        task.setAssetStatuses(rows);
        int admitted = (int) rows.stream().filter(r -> SOFT_DELETED.equals(r.getStatus())).count();
        int refused = (int) rows.stream().filter(r -> REFUSED.equals(r.getStatus()) || FAILED.equals(r.getStatus()))
                .count();
        task.setAssetsProcessedSuccessfully(admitted);
        task.setAssetsFailedToProcess(refused);
        task.setAssetsPendingProcessing(rows.size() - admitted - refused);
        if (waiting) {
            task.setNextCheckAt(now.plus(properties.sweep().approvalPollInterval()));
        } else {
            task.setNextCheckAt(null);
            task.setCompletedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            boolean allRejected = !rows.isEmpty() && rows.stream().allMatch(r ->
                    RefusalReason.APPROVAL_REJECTED.code().equals(r.getErrorMessage()));
            task.setStatus(admitted == rows.size() && admitted > 0 ? DeletionStatus.COMPLETED
                    : admitted > 0 ? DeletionStatus.PARTIALLY_COMPLETED
                    : allRejected ? DeletionStatus.REJECTED : DeletionStatus.FAILED);
            LOG.info("Task {} finished: {} admitted, {} refused", task.getTaskId(), admitted, refused);
        }
        tasks.save(task);
    }

    private static void finish(AssetDeletionStatusData row, String status, String error, Instant now) {
        row.setStatus(status);
        row.setErrorMessage(error);
        row.setProcessedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    private static AssetDeletionStatusData copy(AssetDeletionStatusData r) {
        return new AssetDeletionStatusData(r.getAssetId(), r.getAssetName(), r.getStatus(), r.getDeletionType(),
                r.getErrorMessage(), r.getProcessedAt(), r.getApprovalId(), r.getSoftDeleteRecordId());
    }
}
