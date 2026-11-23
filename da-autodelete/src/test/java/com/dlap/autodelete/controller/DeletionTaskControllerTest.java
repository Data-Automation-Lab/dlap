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
import com.dlap.autodelete.service.exception.DeletionTaskNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = DeletionTaskController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        // Spring Boot 4: the OAuth2 client auto-configuration lives in a module this service does not
        // have, so it no longer needs excluding (BOOT-01).
        OAuth2ResourceServerAutoConfiguration.class
    }
)
class DeletionTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDeletionTaskService taskService;

    @Autowired
    private ObjectMapper objectMapper;

    private DeletionTaskRequest sampleTaskRequest;
    private DeletionTaskResponse sampleTaskResponse;
    private DeletionTaskStatusDTO sampleTaskStatusDTO;
    private String sampleTaskId;

    @BeforeEach
    void setUp() {
        sampleTaskId = UUID.randomUUID().toString();

        DeletionTaskRequest.DeletionScope scope = DeletionTaskRequest.DeletionScope.builder()
                .assetIds(List.of("asset1", "asset2"))
                .build();
        sampleTaskRequest = DeletionTaskRequest.builder()
                .taskName("Test Deletion Task")
                .scope(scope)
                .build();

        sampleTaskResponse = DeletionTaskResponse.builder()
                .taskId(sampleTaskId)
                .taskName("Test Deletion Task")
                .status("SUBMITTED")
                .submittedAt(LocalDateTime.now())
                .build();
        
        sampleTaskStatusDTO = DeletionTaskStatusDTO.builder()
                .taskId(sampleTaskId)
                .taskName("Test Deletion Task")
                .status("SUBMITTED")
                .submittedAt(LocalDateTime.now())
                .scope(scope) // Assuming DeletionTaskRequest.DeletionScope is compatible or mapped
                .totalAssetsInScope(2)
                .build();
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void submitDeletionTask_AsAdmin_ValidRequest_ShouldReturnAccepted() throws Exception {
        when(taskService.submitDeletionTask(any(DeletionTaskRequest.class), any())).thenReturn(sampleTaskResponse);

        mockMvc.perform(post("/api/v1/deletion/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleTaskRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(sampleTaskId))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }
    
    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void submitDeletionTask_InvalidScope_ShouldReturnBadRequest() throws Exception {
        // scope is @NotNull on DeletionTaskRequest, so @Valid rejects the
        // request at the binding layer - the service is never invoked and the
        // response is Spring's bean-validation 400, not the service-level
        // FAILED_VALIDATION shape.
        DeletionTaskRequest invalidRequest = DeletionTaskRequest.builder().taskName("Invalid Task").scope(null).build();

        mockMvc.perform(post("/api/v1/deletion/tasks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskService);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void getDeletionTaskStatus_WhenTaskExists_ShouldReturnStatus() throws Exception {
        when(taskService.getTaskStatus(sampleTaskId)).thenReturn(sampleTaskStatusDTO);

        mockMvc.perform(get("/api/v1/deletion/tasks/{taskId}", sampleTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(sampleTaskId))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void getDeletionTaskStatus_WhenTaskNotExists_ShouldReturnNotFound() throws Exception {
        when(taskService.getTaskStatus(sampleTaskId)).thenReturn(null); // Service returns null for not found

        mockMvc.perform(get("/api/v1/deletion/tasks/{taskId}", sampleTaskId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void listDeletionTasks_ShouldReturnPageOfTasks() throws Exception {
        List<DeletionTaskStatusDTO> taskList = Collections.singletonList(sampleTaskStatusDTO);
        PageImpl<DeletionTaskStatusDTO> page = new PageImpl<>(taskList, PageRequest.of(0, 20), 1);
        DeletionTaskListResponse listResponse = DeletionTaskListResponse.fromPage(page);

        when(taskService.listTasks(any(Pageable.class))).thenReturn(listResponse);

        mockMvc.perform(get("/api/v1/deletion/tasks").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskId").value(sampleTaskId));
    }

    /** UI-04 (Q-623): the window end goes out as an ISO-8601 instant, and an absent one as null. */
    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void listDeletionTasks_CarriesTheWindowEndAsAnIsoInstantAndNullWhenThereIsNone() throws Exception {
        DeletionTaskStatusDTO withWindow = DeletionTaskStatusDTO.builder().taskId("a").status("QUEUED")
                .scheduledHardDeleteAt(java.time.Instant.parse("2026-11-09T10:15:30Z")).build();
        DeletionTaskStatusDTO withoutWindow = DeletionTaskStatusDTO.builder().taskId("b").status("QUEUED").build();
        PageImpl<DeletionTaskStatusDTO> page = new PageImpl<>(List.of(withWindow, withoutWindow),
                PageRequest.of(0, 20), 2);
        when(taskService.listTasks(any(Pageable.class))).thenReturn(DeletionTaskListResponse.fromPage(page));

        mockMvc.perform(get("/api/v1/deletion/tasks").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].scheduledHardDeleteAt").value("2026-11-09T10:15:30Z"))
                .andExpect(jsonPath("$.tasks[1].scheduledHardDeleteAt").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void approveDeletionTask_AsAdmin_WhenTaskApprovable_ShouldReturnOk() throws Exception {
        DeletionTaskStatusDTO approvedStatus = DeletionTaskStatusDTO.builder().taskId(sampleTaskId).status("APPROVED").build();
        when(taskService.approveTask(eq(sampleTaskId), any())).thenReturn(approvedStatus);

        mockMvc.perform(post("/api/v1/deletion/tasks/{taskId}/approve", sampleTaskId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }
    
    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void approveDeletionTask_TaskNotApprovable_ShouldReturnConflict() throws Exception {
        DeletionTaskStatusDTO conflictStatus = DeletionTaskStatusDTO.builder()
                                                .taskId(sampleTaskId)
                                                .status("COMPLETED") // Example of a non-approvable state
                                                .errorMessages(List.of("Task not in approvable state"))
                                                .build();
        when(taskService.approveTask(eq(sampleTaskId), any())).thenReturn(conflictStatus);

        mockMvc.perform(post("/api/v1/deletion/tasks/{taskId}/approve", sampleTaskId)
                        .with(csrf()))
                .andExpect(status().isConflict()) // Controller maps this from DTO having errorMessages
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void rejectDeletionTask_AsDataSteward_WhenTaskRejectable_ShouldReturnOk() throws Exception {
        DeletionTaskStatusDTO rejectedStatus = DeletionTaskStatusDTO.builder().taskId(sampleTaskId).status("REJECTED").build();
        when(taskService.rejectTask(eq(sampleTaskId), any())).thenReturn(rejectedStatus);

        mockMvc.perform(post("/api/v1/deletion/tasks/{taskId}/reject", sampleTaskId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
} 