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

package com.dlap.autodelete.service;

import com.dlap.autodelete.dto.DeletionTaskRequest;
import com.dlap.autodelete.dto.DeletionTaskResponse;
import com.dlap.autodelete.dto.DeletionTaskStatusDTO;
import com.dlap.autodelete.dto.DeletionTaskListResponse;
import com.dlap.autodelete.dto.TaskApprovalRequest;
import org.springframework.data.domain.Pageable;

public interface IDeletionTaskService {

    /**
     * Submits a new task for asset deletion. Nothing is deleted here: an approval is requested for
     * every asset on behalf of the requester, who can therefore never approve it (RET-02).
     *
     * @param request          the deletion task request; its {@code triggeredBy} and
     *                         {@code overrideConfig.requireApproval} are ignored
     * @param requesterSubject the JWT subject of the caller
     * @return the task id and initial status
     */
    DeletionTaskResponse submitDeletionTask(DeletionTaskRequest request, String requesterSubject);

    /**
     * Retrieves the status of a specific deletion task.
     * @param taskId The ID of the task.
     * @return The status DTO of the task, or null if not found.
     */
    DeletionTaskStatusDTO getTaskStatus(String taskId);

    /**
     * Lists all deletion tasks with pagination.
     * @param pageable Pagination information.
     * @return A paginated list of task statuses.
     */
    DeletionTaskListResponse listTasks(Pageable pageable);

    /**
     * Retired (RET-02): approvals are decided in the policy engine. Answers the task with an error
     * message that says so.
     * @param taskId The ID of the task to approve.
     * @param approvalRequest Details for the approval (e.g., comments).
     * @return The updated status DTO of the task.
     */
    DeletionTaskStatusDTO approveTask(String taskId, TaskApprovalRequest approvalRequest);

    /**
     * Retired (RET-02): approvals are decided in the policy engine. Answers the task with an error
     * message that says so.
     * @param taskId The ID of the task to reject.
     * @param rejectionRequest Details for the rejection (e.g., comments).
     * @return The updated status DTO of the task.
     */
    DeletionTaskStatusDTO rejectTask(String taskId, TaskApprovalRequest rejectionRequest);
}
