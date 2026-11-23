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

package com.dlap.autolabel.controller;

import com.dlap.autolabel.client.rest.dto.*;
import com.dlap.autolabel.exception.AutoLabelingException;
import com.dlap.autolabel.service.ILabelingJobService;
import com.dlap.common.constants.DlapConstants;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = LabelingJobController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        // Spring Boot 4: the OAuth2 client auto-configuration lives in a module this service does not
        // have, so it no longer needs excluding (BOOT-01).
        OAuth2ResourceServerAutoConfiguration.class
    }
)
class LabelingJobControllerTest {

    // The authorities the controller's @PreAuthorize expressions name. The tests use the real
    // DLAP roles (DlapConstants.UserRole); ROLE_DATA_STEWARD and ROLE_USER do not exist.
    private static final String POLICY_MANAGER = "ROLE_" + DlapConstants.UserRole.POLICY_MANAGER;
    private static final String DATA_ANALYST = "ROLE_" + DlapConstants.UserRole.DATA_ANALYST;
    private static final String VIEWER = "ROLE_" + DlapConstants.UserRole.VIEWER;
    private static final String SERVICE_ACCOUNT = "ROLE_" + DlapConstants.UserRole.SERVICE_ACCOUNT;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ILabelingJobService labelingJobService;

    @Autowired
    private ObjectMapper objectMapper;

    private LabelingJobRequest labelingJobRequest;
    private LabelingJobResponse labelingJobResponse;
    private LabelingJobStatusResponse labelingJobStatusResponse;
    private LabelingFeedbackRequest labelingFeedbackRequest;
    private LabelingFeedbackResponse labelingFeedbackResponse;

    @BeforeEach
    void setUp() {
        String jobId = UUID.randomUUID().toString();
        String catalogAssetId = UUID.randomUUID().toString();
        labelingJobRequest = LabelingJobRequest.builder()
                .jobName("Test Job")
                .scope(LabelingJobRequest.LabelingScope.builder()
                        .assetIds(List.of(UUID.randomUUID().toString()))
                        .build())
                .build();
        labelingJobResponse = LabelingJobResponse.builder()
                .jobId(jobId)
                .status("SUBMITTED")
                .submittedAt(LocalDateTime.now())
                .message("Job submitted")
                .build();
        labelingJobStatusResponse = LabelingJobStatusResponse.builder()
                .jobId(jobId).jobName("Test Job").status("RUNNING").build();

        labelingFeedbackRequest = LabelingFeedbackRequest.builder()
                .assetId(catalogAssetId)
                .labelingJobId(jobId)
                .feedbackItems(List.of(LabelingFeedbackRequest.FeedbackItem.builder()
                        .suggestedLabel("PII")
                        .type(LabelingFeedbackRequest.FeedbackType.CONFIRMED)
                        .build()))
                .build();
        labelingFeedbackResponse = LabelingFeedbackResponse.builder()
                .feedbackId(UUID.randomUUID().toString())
                .assetId(catalogAssetId)
                .status("RECEIVED")
                .build();
    }

    @Test
    @WithMockUser(authorities = POLICY_MANAGER)
    void submitLabelingJob_ValidRequest_ShouldSucceed() throws Exception {
        when(labelingJobService.submitLabelingJob(any(LabelingJobRequest.class))).thenReturn(labelingJobResponse);

        mockMvc.perform(post("/api/v1/labeling/jobs")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingJobRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(labelingJobResponse.getJobId()));
    }
    
    @Test
    @WithMockUser(authorities = DATA_ANALYST) // Reader tier: may read jobs, may not submit them
    void submitLabelingJob_ReaderRole_ShouldBeForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/labeling/jobs")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingJobRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getJobStatus_ExistingJob_ShouldReturnStatus() throws Exception {
        when(labelingJobService.getJobStatus(eq(labelingJobStatusResponse.getJobId()))).thenReturn(labelingJobStatusResponse);

        mockMvc.perform(get("/api/v1/labeling/jobs/{jobId}", labelingJobStatusResponse.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(labelingJobStatusResponse.getJobId()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getJobStatus_NonExistingJob_ShouldReturnNotFound() throws Exception {
        String nonExistentJobId = UUID.randomUUID().toString();
        when(labelingJobService.getJobStatus(eq(nonExistentJobId))).thenReturn(null);

        mockMvc.perform(get("/api/v1/labeling/jobs/{jobId}", nonExistentJobId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void listJobs_ShouldReturnJobList() throws Exception {
        LabelingJobListResponse listResponse = LabelingJobListResponse.builder()
                .jobs(Collections.singletonList(labelingJobStatusResponse))
                .pageNumber(0).pageSize(20).totalElements(1L).totalPages(1).last(true)
                .build();
        when(labelingJobService.listJobs(any(Pageable.class))).thenReturn(listResponse);

        mockMvc.perform(get("/api/v1/labeling/jobs").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].jobId").value(labelingJobStatusResponse.getJobId()));
    }

    @Test
    @WithMockUser(authorities = POLICY_MANAGER)
    void submitLabelingFeedback_ValidRequest_ShouldSucceed() throws Exception {
        when(labelingJobService.processLabelingFeedback(any(LabelingFeedbackRequest.class))).thenReturn(labelingFeedbackResponse);

        mockMvc.perform(post("/api/v1/labeling/feedback")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingFeedbackRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackId").value(labelingFeedbackResponse.getFeedbackId()));
    }
    
    @Test
    @WithMockUser(authorities = DATA_ANALYST) // Reader tier may not submit feedback
    void submitLabelingFeedback_ReaderRole_ShouldBeForbidden() throws Exception {
         mockMvc.perform(post("/api/v1/labeling/feedback")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingFeedbackRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = POLICY_MANAGER)
    void submitLabelingJob_ServiceThrowsAutoLabelingException_ShouldReturnBadRequest() throws Exception {
        when(labelingJobService.submitLabelingJob(any(LabelingJobRequest.class)))
            .thenThrow(new AutoLabelingException("Test ML config error"));

        mockMvc.perform(post("/api/v1/labeling/jobs")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingJobRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Test ML config error"));
    }

    @Test
    @WithMockUser(authorities = SERVICE_ACCOUNT)
    void listJobs_ServiceAccount_ShouldSucceed() throws Exception {
        // da-admin-service lists jobs with a client-credentials token (M2M, ADR-0034).
        LabelingJobListResponse listResponse = LabelingJobListResponse.builder()
                .jobs(Collections.emptyList())
                .pageNumber(0).pageSize(20).totalElements(0L).totalPages(0).last(true)
                .build();
        when(labelingJobService.listJobs(any(Pageable.class))).thenReturn(listResponse);

        mockMvc.perform(get("/api/v1/labeling/jobs"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = SERVICE_ACCOUNT)
    void submitLabelingJob_ServiceAccount_ShouldBeForbidden() throws Exception {
        // The machine principal is granted the job listing and nothing else (least privilege).
        mockMvc.perform(post("/api/v1/labeling/jobs")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingJobRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = VIEWER)
    void getJobStatus_ViewerRole_ShouldBeForbidden() throws Exception {
        // VIEWER is a DLAP role but is not on the job-status expression.
        mockMvc.perform(get("/api/v1/labeling/jobs/{jobId}", labelingJobStatusResponse.getJobId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_DATA_STEWARD")
    void submitLabelingJob_UnknownLegacyRole_ShouldBeForbidden() throws Exception {
        // ROLE_DATA_STEWARD is not a DLAP role. Pin that it grants nothing, so a stale test or
        // client that still uses it fails loudly instead of passing for the wrong reason.
        mockMvc.perform(post("/api/v1/labeling/jobs")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(labelingJobRequest)))
                .andExpect(status().isForbidden());
    }
}
