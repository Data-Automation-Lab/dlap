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

package com.dlap.autoarchival.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.autoarchival.dto.ArchivalTaskListResponse;
import com.dlap.autoarchival.dto.ArchivalTaskRequest;
import com.dlap.autoarchival.dto.ArchivalTaskResponse;
import com.dlap.autoarchival.dto.ArchivalTaskStatusDTO;
import com.dlap.autoarchival.dto.RestoreRequestDTO;
import com.dlap.autoarchival.dto.RestoreResponseDTO;
import com.dlap.autoarchival.dto.TaskApprovalRequest;
import com.dlap.autoarchival.service.IArchivalTaskService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/archival/tasks")
@RequiredArgsConstructor
@Slf4j
public class ArchivalTaskController {

    private final IArchivalTaskService archivalTaskService;

    /**
     * Submits a new archival task for specified assets.
     * Requires DATA_STEWARD or ADMIN role.
     *
     * @param taskRequest The archival task request details.
     * @return ResponseEntity with ArchivalTaskResponse indicating task submission status.
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    public ResponseEntity<ArchivalTaskResponse> submitArchivalTask(@Valid @RequestBody ArchivalTaskRequest taskRequest,
                                                                   Authentication authentication) {
        log.info("Received request to submit archival task: {}", taskRequest.getTaskName());
        try {
            // The caller is the JWT sub: it is named in the approval request, so it can never decide it.
            ArchivalTaskResponse response = archivalTaskService.submitArchivalTask(taskRequest,
                    authentication == null ? null : authentication.getName());
            if ("FAILED_VALIDATION".equals(response.getStatus())) {
                return ResponseEntity.badRequest().body(response);
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (Exception e) {
            log.error("Unexpected error submitting archival task '{}': {}", taskRequest.getTaskName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ArchivalTaskResponse.builder()
                            .taskName(taskRequest.getTaskName())
                            .status("ERROR")
                            .message("An unexpected error occurred while submitting the archival task.")
                            .submittedAt(java.time.LocalDateTime.now())
                            .build());
        }
    }

    /**
     * Retrieves the status of a specific archival task.
     * Requires DATA_STEWARD, ADMIN, or USER role (user who submitted, or involved).
     *
     * @param taskId The ID of the archival task.
     * @return ResponseEntity with ArchivalTaskStatusDTO or Not Found.
     */
    @GetMapping("/{taskId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')") // User role may need further refinement (e.g., task owner)
    public ResponseEntity<ArchivalTaskStatusDTO> getArchivalTaskStatus(@PathVariable String taskId) {
        log.info("Request to retrieve status for archival task ID: {}", taskId);
        ArchivalTaskStatusDTO statusDTO = archivalTaskService.getTaskStatus(taskId);
        if (statusDTO != null) {
            return ResponseEntity.ok(statusDTO);
        } else {
            log.warn("Archival task with ID '{}' not found.", taskId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Lists all archival tasks with pagination.
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @param pageable Pagination information.
     * @return ResponseEntity with ArchivalTaskListResponse.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account (da-admin)
    public ResponseEntity<ArchivalTaskListResponse> listArchivalTasks(@PageableDefault(size = 20) Pageable pageable) {
        log.info("Request to list archival tasks. Pagination: {}", pageable);
        try {
            ArchivalTaskListResponse response = archivalTaskService.listTasks(pageable);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error listing archival tasks: {}", e.getMessage(), e);
            // Consider a more specific error response DTO if needed
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Approves a pending archival task.
     * Requires ADMIN or designated approver role.
     */
    @PostMapping("/{taskId}/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')") // Or a more specific approver role
    public ResponseEntity<ArchivalTaskStatusDTO> approveArchivalTask(@PathVariable String taskId, 
                                                                   @RequestBody(required = false) TaskApprovalRequest approvalRequest) {
        log.info("Request to approve archival task ID: {}. Comments: {}", taskId, approvalRequest != null ? approvalRequest.getComments() : "N/A");
        ArchivalTaskStatusDTO updatedStatus = archivalTaskService.approveTask(taskId, approvalRequest);
        if (updatedStatus == null) {
            return ResponseEntity.notFound().build();
        }
        if ("APPROVED".equals(updatedStatus.getStatus()) || "SUBMITTED".equals(updatedStatus.getStatus())) { // Or other success states
            return ResponseEntity.ok(updatedStatus);
        } else {
            // Task might not have been in an approvable state
            return ResponseEntity.status(HttpStatus.CONFLICT).body(updatedStatus);
        }
    }

    /**
     * Rejects a pending archival task.
     * Requires ADMIN or designated approver role.
     */
    @PostMapping("/{taskId}/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')") // Or a more specific approver role
    public ResponseEntity<ArchivalTaskStatusDTO> rejectArchivalTask(@PathVariable String taskId, 
                                                                  @RequestBody(required = false) TaskApprovalRequest rejectionRequest,
                                                                  Authentication authentication) {
        log.info("Request to reject archival task ID: {}. Comments: {}", taskId, rejectionRequest != null ? rejectionRequest.getComments() : "N/A");
        ArchivalTaskStatusDTO updatedStatus = archivalTaskService.rejectTask(taskId, rejectionRequest,
                authentication == null ? null : authentication.getName());
        if (updatedStatus == null) {
            return ResponseEntity.notFound().build();
        }
        if ("REJECTED".equals(updatedStatus.getStatus())) {
            return ResponseEntity.ok(updatedStatus);
        } else {
            // Task might not have been in a rejectable state
            return ResponseEntity.status(HttpStatus.CONFLICT).body(updatedStatus);
        }
    }

    /**
     * Requests restoration of assets associated with an archival task.
     * Requires ADMIN or DATA_STEWARD role.
     *
     * @param taskId The ID of the archival task whose assets are to be restored.
     * @param restoreRequest DTO containing restore parameters.
     * @return ResponseEntity with RestoreResponseDTO indicating the outcome of the restore request.
     */
    @PostMapping("/{taskId}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    public ResponseEntity<RestoreResponseDTO> requestRestoreArchivalTask(@PathVariable String taskId, 
                                                                       @Valid @RequestBody RestoreRequestDTO restoreRequest,
                                                                       Authentication authentication) {
        log.info("Request to restore assets for archival task ID: {}. Restore tier: {}", taskId, restoreRequest.getRestoreTier());
        try {
            // Who restores is taken from the token, never from the body (the audit log names the caller).
            restoreRequest.setRequestedBy(authentication == null ? null : authentication.getName());
            RestoreResponseDTO response = archivalTaskService.requestRestore(taskId, restoreRequest);
            if (response != null) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            } else {
                log.warn("Archival task with ID '{}' not found for restore request.", taskId);
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Unexpected error requesting restore for archival task '{}': {}", taskId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestoreResponseDTO.builder()
                            .archivalTaskId(taskId)
                            .status("ERROR")
                            .message("An unexpected error occurred while processing the restore request.")
                            .requestedAt(java.time.LocalDateTime.now())
                            .build());
        }
    }
} 