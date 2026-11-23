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

package com.dlap.autoarchival.service;

import com.dlap.autoarchival.dto.ArchivalTaskListResponse;
import com.dlap.autoarchival.dto.ArchivalTaskRequest;
import com.dlap.autoarchival.dto.ArchivalTaskResponse;
import com.dlap.autoarchival.dto.ArchivalTaskStatusDTO;
import com.dlap.autoarchival.dto.TaskApprovalRequest;
import com.dlap.autoarchival.dto.RestoreRequestDTO;
import com.dlap.autoarchival.dto.RestoreResponseDTO;
// Potentially add other DTOs like ArchivalTaskSummaryDTO if needed for other methods
import org.springframework.data.domain.Pageable;

public interface IArchivalTaskService {

    /**
     * Submits a new archival task.
     *
     * @param request The archival task request.
     * @return ArchivalTaskResponse indicating the outcome of the submission.
     */
    ArchivalTaskResponse submitArchivalTask(ArchivalTaskRequest request);

    /**
     * Submits a new archival task on behalf of a person. Each asset asks the policy engine for an
     * approval that this person can never decide (docs/specs/approvals.md section 5), and the archive
     * runs only when that approval is executable.
     *
     * @param request      The archival task request.
     * @param requesterSub The JWT {@code sub} of the person asking, or {@code null}.
     * @return ArchivalTaskResponse indicating the outcome of the submission.
     */
    ArchivalTaskResponse submitArchivalTask(ArchivalTaskRequest request, String requesterSub);

    /**
     * Retrieves the status of a specific archival task.
     *
     * @param taskId The ID of the task.
     * @return ArchivalTaskStatusDTO containing the detailed status, or null if not found.
     */
    ArchivalTaskStatusDTO getTaskStatus(String taskId);

    /**
     * Lists all archival tasks with pagination.
     *
     * @param pageable Pagination information.
     * @return ArchivalTaskListResponse containing the paginated list of tasks.
     */
    ArchivalTaskListResponse listTasks(Pageable pageable);

    /**
     * Approves a task.
     *
     * @param taskId The ID of the task to be approved.
     * @param approvalRequest DTO containing approval parameters.
     * @return ArchivalTaskStatusDTO indicating the outcome of the approval.
     */
    ArchivalTaskStatusDTO approveTask(String taskId, TaskApprovalRequest approvalRequest);

    /**
     * Rejects a task.
     *
     * @param taskId The ID of the task to be rejected.
     * @param rejectionRequest DTO containing rejection parameters.
     * @param rejectedBy The JWT {@code sub} of the operator rejecting, recorded in the audit log; may be {@code null}.
     * @return ArchivalTaskStatusDTO indicating the outcome of the rejection.
     */
    ArchivalTaskStatusDTO rejectTask(String taskId, TaskApprovalRequest rejectionRequest, String rejectedBy);

    /**
     * Requests restoration of assets associated with an archival task.
     *
     * @param taskId The ID of the original archival task whose assets are to be restored.
     * @param restoreRequest DTO containing restore parameters.
     * @return RestoreResponseDTO indicating the outcome of the restore request.
     */
    RestoreResponseDTO requestRestore(String taskId, RestoreRequestDTO restoreRequest);

    /**
     * Processes archival action from policy engine events.
     *
     * @param event The policy action event containing archival instructions.
     */
    void processArchivalAction(java.util.Map<String, Object> event);

    /**
     * Plans a dry-run archive from a policy event and logs what it would do. Read only: nothing is
     * written at the provider, no task is created and nothing goes to the audit log. Never throws.
     *
     * @param event The policy action event ({@code dryRun: true}).
     */
    void logDryRun(java.util.Map<String, Object> event);

    /**
     * Reacts to a decided approval (topic {@code dlap.approvals.decided}): an executable approval starts
     * the archive that waits on it, a rejected or expired one ends it. An approval this service never
     * stored is ignored. The event is only a prompt; the executor re-reads the approval before it acts.
     *
     * @param approvalId the approval id
     * @param executable whether the event says the request is executable
     * @param status     the final status the event reports
     */
    void onApprovalDecided(String approvalId, boolean executable, String status);

    /**
     * Asynchronously runs the archive that waits on an approval.
     *
     * @param approvalId the approval id
     */
    void executeApprovalAsync(String approvalId);

    /**
     * Asynchronously executes the archival work for an approved task by
     * dispatching to the appropriate {@code ICloudStorageService}.
     *
     * @param taskId the archival task identifier
     */
    void processArchivalTaskAsync(String taskId);

    /**
     * Asynchronously executes the restoration work for a task whose restore has
     * been requested by dispatching to the appropriate {@code ICloudStorageService}.
     *
     * @param taskId the archival task identifier
     */
    void processRestorationAsync(String taskId);

    // Future methods:
    // Page<ArchivalTaskSummaryDTO> listTasks(Pageable pageable);
    // void approveTask(String taskId, String approverComments);
    // void rejectTask(String taskId, String rejectorComments);
    // ArchivalTaskResponse requestRestore(String taskId, RestoreRequestDTO restoreRequest);
} 