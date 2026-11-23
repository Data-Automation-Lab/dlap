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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.autoarchival.dto.ArchivalTaskListResponse;
import com.dlap.autoarchival.dto.ArchivalTaskRequest;
import com.dlap.autoarchival.dto.ArchivalTaskResponse;
import com.dlap.autoarchival.dto.ArchivalTaskStatusDTO;
import com.dlap.autoarchival.dto.RestoreRequestDTO;
import com.dlap.autoarchival.dto.RestoreResponseDTO;
import com.dlap.autoarchival.service.IArchivalTaskService;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(ArchivalTaskController.class)
class ArchivalTaskControllerTest {

    // The authorities the controller's @PreAuthorize expressions name (DlapConstants.UserRole).
    // ROLE_USER is not a DLAP role and grants nothing.
    private static final String POLICY_MANAGER = "ROLE_" + DlapConstants.UserRole.POLICY_MANAGER;
    private static final String DATA_ANALYST = "ROLE_" + DlapConstants.UserRole.DATA_ANALYST;
    private static final String VIEWER = "ROLE_" + DlapConstants.UserRole.VIEWER;
    private static final String SERVICE_ACCOUNT = "ROLE_" + DlapConstants.UserRole.SERVICE_ACCOUNT;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IArchivalTaskService taskService;

    @Autowired
    private ObjectMapper objectMapper;

    private ArchivalTaskRequest sampleTaskRequest;
    private ArchivalTaskResponse sampleTaskResponse;
    private ArchivalTaskStatusDTO sampleTaskStatusDTO;
    private String sampleTaskId;

    @BeforeEach
    void setUp() {
        sampleTaskId = UUID.randomUUID().toString();

        ArchivalTaskRequest.ArchivalScope scope = ArchivalTaskRequest.ArchivalScope.builder()
                .assetIds(List.of("asset1", "asset2"))
                .build();
        sampleTaskRequest = ArchivalTaskRequest.builder()
                .taskName("Test Archival Task")
                .scope(scope)
                .build();

        sampleTaskResponse = ArchivalTaskResponse.builder()
                .taskId(sampleTaskId)
                .taskName("Test Archival Task")
                .status("SUBMITTED")
                .submittedAt(LocalDateTime.now())
                .build();
        
        sampleTaskStatusDTO = ArchivalTaskStatusDTO.builder()
                .taskId(sampleTaskId)
                .taskName("Test Archival Task")
                .status("SUBMITTED")
                .submittedAt(LocalDateTime.now())
                .scope(scope)
                .totalAssetsInScope(2)
                .build();
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void submitArchivalTask_AsAdmin_ShouldReturnAccepted() throws Exception {
        when(taskService.submitArchivalTask(any(ArchivalTaskRequest.class), any())).thenReturn(sampleTaskResponse);

        mockMvc.perform(post("/api/v1/archival/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleTaskRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(sampleTaskId))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }
    
    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void submitArchivalTask_WithInvalidScope_ShouldReturnBadRequest() throws Exception {
        ArchivalTaskRequest invalidRequest = ArchivalTaskRequest.builder().taskName("Invalid Task").scope(null).build();
        ArchivalTaskResponse validationResponse = ArchivalTaskResponse.builder()
                                .status("FAILED_VALIDATION").message("Scope is null").build();
        when(taskService.submitArchivalTask(any(ArchivalTaskRequest.class), any())).thenReturn(validationResponse);

        mockMvc.perform(post("/api/v1/archival/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getArchivalTaskStatus_WhenTaskExists_ShouldReturnStatus() throws Exception {
        when(taskService.getTaskStatus(sampleTaskId)).thenReturn(sampleTaskStatusDTO);

        mockMvc.perform(get("/api/v1/archival/tasks/{taskId}", sampleTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(sampleTaskId))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getArchivalTaskStatus_WhenTaskNotExists_ShouldReturnNotFound() throws Exception {
        when(taskService.getTaskStatus(sampleTaskId)).thenReturn(null);

        mockMvc.perform(get("/api/v1/archival/tasks/{taskId}", sampleTaskId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void listArchivalTasks_ShouldReturnPageOfTasks() throws Exception {
        ArchivalTaskListResponse listResponse = ArchivalTaskListResponse.builder()
                .tasks(Collections.singletonList(sampleTaskStatusDTO))
                .pageNumber(0).pageSize(20).totalElements(1).totalPages(1).last(true).first(true).numberOfElements(1)
                .build();
        when(taskService.listTasks(any(Pageable.class))).thenReturn(listResponse);

        mockMvc.perform(get("/api/v1/archival/tasks").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskId").value(sampleTaskId));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void approveArchivalTask_AsAdmin_ShouldReturnOk() throws Exception {
        ArchivalTaskStatusDTO approvedStatus = ArchivalTaskStatusDTO.builder().taskId(sampleTaskId).status("APPROVED").build();
        when(taskService.approveTask(eq(sampleTaskId), any())).thenReturn(approvedStatus);

        mockMvc.perform(post("/api/v1/archival/tasks/{taskId}/approve", sampleTaskId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }
    
    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void approveArchivalTask_NotApprovable_ShouldReturnConflict() throws Exception {
        ArchivalTaskStatusDTO conflictStatus = ArchivalTaskStatusDTO.builder().taskId(sampleTaskId).status("COMPLETED").build();
        when(taskService.approveTask(eq(sampleTaskId), any())).thenReturn(conflictStatus);

        mockMvc.perform(post("/api/v1/archival/tasks/{taskId}/approve", sampleTaskId)
                        .with(csrf()))
                .andExpect(status().isConflict());
    }


    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void rejectArchivalTask_AsAdmin_ShouldReturnOk() throws Exception {
        ArchivalTaskStatusDTO rejectedStatus = ArchivalTaskStatusDTO.builder().taskId(sampleTaskId).status("REJECTED").build();
        when(taskService.rejectTask(eq(sampleTaskId), any(), any())).thenReturn(rejectedStatus);

        mockMvc.perform(post("/api/v1/archival/tasks/{taskId}/reject", sampleTaskId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void requestRestoreArchivalTask_AsAdmin_ShouldReturnAccepted() throws Exception {
        RestoreRequestDTO restoreRequest = RestoreRequestDTO.builder()
                .assetIds(List.of("asset1", "asset2"))
                .restoreTier("STANDARD")
                .retentionDays(7)
                .build();
        RestoreResponseDTO restoreResponse = RestoreResponseDTO.builder().archivalTaskId(sampleTaskId).status("RESTORATION_REQUESTED").build();
        when(taskService.requestRestore(eq(sampleTaskId), any(RestoreRequestDTO.class))).thenReturn(restoreResponse);

        mockMvc.perform(post("/api/v1/archival/tasks/{taskId}/restore", sampleTaskId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(restoreRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RESTORATION_REQUESTED"));
    }

    @Test
    @WithMockUser(authorities = SERVICE_ACCOUNT)
    void listArchivalTasks_ServiceAccount_ShouldSucceed() throws Exception {
        // da-admin-service lists tasks with a client-credentials token (M2M, ADR-0034).
        ArchivalTaskListResponse listResponse = ArchivalTaskListResponse.builder()
                .tasks(Collections.emptyList())
                .pageNumber(0).pageSize(20).totalElements(0).totalPages(0).last(true).first(true).numberOfElements(0)
                .build();
        when(taskService.listTasks(any(Pageable.class))).thenReturn(listResponse);

        mockMvc.perform(get("/api/v1/archival/tasks"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = SERVICE_ACCOUNT)
    void getArchivalTaskStatus_ServiceAccount_ShouldBeForbidden() throws Exception {
        // The machine principal may list tasks and nothing else (least privilege).
        mockMvc.perform(get("/api/v1/archival/tasks/{taskId}", sampleTaskId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = VIEWER)
    void getArchivalTaskStatus_Viewer_ShouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/archival/tasks/{taskId}", sampleTaskId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void submitArchivalTask_AsDataAnalyst_ShouldBeForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/archival/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleTaskRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = POLICY_MANAGER)
    void approveArchivalTask_AsPolicyManager_ShouldBeForbidden() throws Exception {
        // Approval is ADMIN only; a policy manager can submit and restore but not approve.
        mockMvc.perform(post("/api/v1/archival/tasks/{taskId}/approve", sampleTaskId)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
