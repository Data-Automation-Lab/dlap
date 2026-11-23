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

package com.dlap.autodelete.controller;

import com.dlap.autodelete.dto.*;
import com.dlap.autodelete.service.IDeletionTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/deletion/tasks")
@Tag(name = "Deletion Task API", description = "APIs for managing auto-deletion tasks")
@RequiredArgsConstructor
public class DeletionTaskController {

    private final IDeletionTaskService deletionTaskService;

    @Operation(summary = "Submit a new deletion task",
               responses = {
                   @ApiResponse(responseCode = "202", description = "Task accepted for processing or approval"),
                   @ApiResponse(responseCode = "400", description = "Invalid request payload or validation error")
               })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<DeletionTaskResponse> submitDeletionTask(@Valid @RequestBody DeletionTaskRequest request,
            @Parameter(hidden = true) @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.oauth2.jwt.Jwt jwt) {
        // RET-02: the requester is the caller's JWT subject, never the body's triggeredBy.
        DeletionTaskResponse response = deletionTaskService.submitDeletionTask(request,
                jwt == null ? null : jwt.getSubject());
        if ("FAILED_VALIDATION".equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "Get deletion task status by ID",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved task status"),
                   @ApiResponse(responseCode = "404", description = "Task not found")
               })
    @GetMapping("/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<DeletionTaskStatusDTO> getDeletionTaskStatus(@Parameter(description = "ID of the deletion task") @PathVariable String taskId) {
        DeletionTaskStatusDTO status = deletionTaskService.getTaskStatus(taskId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        // TODO: Add logic to check if the authenticated user is allowed to see this task (e.g., if they triggered it)
        return ResponseEntity.ok(status);
    }

    @Operation(summary = "List all deletion tasks",
               description = "Lists deletion tasks with pagination. ADMINs/CDOs see all, others might see a filtered list.")
    @GetMapping
    // Q-624: everyone who can restore (ADMIN, POLICY_MANAGER, DIRECTOR) can also find what to restore.
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER', 'POLICY_MANAGER', 'DIRECTOR', 'SERVICE_ACCOUNT')") // + M2M service account (da-admin)
    public ResponseEntity<DeletionTaskListResponse> listDeletionTasks(@PageableDefault(size = 20) Pageable pageable) {
        // TODO: Service layer should handle filtering based on user role (e.g., non-admins see only their tasks)
        DeletionTaskListResponse response = deletionTaskService.listTasks(pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Retired: approvals are decided in the policy engine",
               description = "Answers 409 with the approval API to use (RET-02: separation of duties is enforced there).",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Task approved successfully"),
                   @ApiResponse(responseCode = "404", description = "Task not found"),
                   @ApiResponse(responseCode = "409", description = "Task is not in a state that can be approved")
               })
    @PostMapping("/{taskId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO')")
    public ResponseEntity<DeletionTaskStatusDTO> approveDeletionTask(
            @Parameter(description = "ID of the task to approve") @PathVariable String taskId,
            @RequestBody(required = false) TaskApprovalRequest approvalRequest) {
        DeletionTaskStatusDTO status = deletionTaskService.approveTask(taskId, approvalRequest);
        if (status == null) { // Should be handled by an exception in a real service normally
            return ResponseEntity.notFound().build();
        }
        if (status.getErrorMessages() != null && !status.getErrorMessages().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(status);
        }
        return ResponseEntity.ok(status);
    }

    @Operation(summary = "Retired: approvals are decided in the policy engine",
            description = "Answers 409 with the approval API to use (RET-02: separation of duties is enforced there).",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Task rejected successfully"),
                    @ApiResponse(responseCode = "404", description = "Task not found"),
                    @ApiResponse(responseCode = "409", description = "Task is not in a state that can be rejected")
            })
    @PostMapping("/{taskId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO')")
    public ResponseEntity<DeletionTaskStatusDTO> rejectDeletionTask(
            @Parameter(description = "ID of the task to reject") @PathVariable String taskId,
            @RequestBody(required = false) TaskApprovalRequest rejectionRequest) {
        DeletionTaskStatusDTO status = deletionTaskService.rejectTask(taskId, rejectionRequest);
        if (status == null) { // Should be handled by an exception
            return ResponseEntity.notFound().build();
        }
        if (status.getErrorMessages() != null && !status.getErrorMessages().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(status);
        }
        return ResponseEntity.ok(status);
    }
} 