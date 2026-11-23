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

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.autolabel.client.rest.dto.LabelingFeedbackRequest;
import com.dlap.autolabel.client.rest.dto.LabelingFeedbackResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobListResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobRequest;
import com.dlap.autolabel.client.rest.dto.LabelingJobResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobStatusResponse;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.autolabel.service.AutoLabelingService;
import com.dlap.autolabel.service.ILabelingJobService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST controller for auto-labeling operations using LLM providers.
 * Provides endpoints for job management, ML configuration, and feedback processing.
 */
@RestController
@RequestMapping("/api/v1/autolabel")
@Tag(name = "Auto-Labeling", description = "LLM-powered auto-labeling operations")
public class AutoLabelingController {

    private static final Logger log = LoggerFactory.getLogger(AutoLabelingController.class);

    private final AutoLabelingService autoLabelingService;
    private final LLMClientFactory llmClientFactory;
    private final ILabelingJobService labelingJobService;

    @Autowired
    public AutoLabelingController(AutoLabelingService autoLabelingService,
                                  LLMClientFactory llmClientFactory,
                                  ILabelingJobService labelingJobService) {
        this.autoLabelingService = autoLabelingService;
        this.llmClientFactory = llmClientFactory;
        this.labelingJobService = labelingJobService;
    }

    @PostMapping("/labeling/jobs")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(summary = "Create a labeling job", description = "Creates a new auto-labeling job for the specified assets")
    public ResponseEntity<LabelingJobResponse> createLabelingJob(
            @Valid @RequestBody LabelingJobRequest request) {
        log.info("REST request to create labeling job: {}", request.getJobName());
        
        try {
            LabelingJobResponse response = autoLabelingService.createLabelingJob(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (Exception e) {
            log.error("Failed to create labeling job: {}", e.getMessage(), e);
            LabelingJobResponse errorResponse = LabelingJobResponse.builder()
                    .jobId(UUID.randomUUID().toString())
                    .status("FAILED")
                    .message("Failed to create labeling job: " + e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/labeling/jobs")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(summary = "Get labeling jobs", description = "Retrieves a paginated list of labeling jobs")
    public ResponseEntity<LabelingJobListResponse> getLabelingJobs(
            @PageableDefault(size = 20, sort = "submittedAt") Pageable pageable,
            @RequestParam(required = false) @Parameter(description = "Filter by job status") String status,
            @RequestParam(required = false) @Parameter(description = "Filter by user ID") UUID userId) {
        log.info("REST request to get labeling jobs with filters: status={}, userId={}", status, userId);

        LabelingJobListResponse response = labelingJobService.listJobs(pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/labeling/jobs/{jobId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(summary = "Get job status", description = "Retrieves the status and details of a specific labeling job")
    public ResponseEntity<LabelingJobStatusResponse> getJobStatus(@PathVariable String jobId) {
        log.info("REST request to get status for job: {}", jobId);

        LabelingJobStatusResponse response = labelingJobService.getJobStatus(jobId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/labeling/feedback")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(summary = "Provide labeling feedback", description = "Submits feedback for improving labeling accuracy")
    public ResponseEntity<LabelingFeedbackResponse> provideFeedback(
            @Valid @RequestBody LabelingFeedbackRequest feedback) {
        log.info("REST request to provide feedback for asset: {}", feedback.getAssetId());
        
        try {
            LabelingFeedbackResponse response = autoLabelingService.processFeedback(feedback);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to process feedback: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/labeling/config/ml")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(summary = "Update ML configuration", description = "Updates the machine learning configuration for auto-labeling")
    public ResponseEntity<Map<String, Object>> updateMLConfiguration(
            @Valid @RequestBody MLConfigRequest mlConfig) {
        log.info("REST request to update ML configuration for provider: {}", mlConfig.getProviderType());
        
        try {
            boolean success = autoLabelingService.updateMLConfiguration(mlConfig);
            
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "ML configuration updated successfully",
                    "provider", mlConfig.getProviderType()
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "ML configuration validation failed",
                    "provider", mlConfig.getProviderType()
                ));
            }
        } catch (Exception e) {
            log.error("Failed to update ML configuration: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "ERROR",
                "message", "Failed to update ML configuration: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/labeling/config/ml/test")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(summary = "Test ML configuration", description = "Tests the connectivity and validity of an ML configuration")
    public ResponseEntity<Map<String, Object>> testMLConfiguration(
            @Valid @RequestBody MLConfigRequest mlConfig) {
        log.info("REST request to test ML configuration for provider: {}", mlConfig.getProviderType());
        
        try {
            boolean connectionOk = llmClientFactory.testConnection(mlConfig);
            boolean configValid = llmClientFactory.validateConfiguration(mlConfig);
            
            Map<String, Object> result = Map.of(
                "provider", mlConfig.getProviderType(),
                "connectionTest", connectionOk ? "PASSED" : "FAILED",
                "configurationTest", configValid ? "VALID" : "INVALID",
                "overallStatus", (connectionOk && configValid) ? "SUCCESS" : "FAILED"
            );
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Failed to test ML configuration: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "ERROR",
                "message", "Failed to test ML configuration: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(summary = "Get available LLM providers", description = "Retrieves information about all supported LLM providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        log.debug("REST request to get available LLM providers");
        
        try {
            Map<String, Map<String, Object>> capabilities = autoLabelingService.getProviderCapabilities();
            
            Map<String, Object> response = Map.of(
                "supportedProviders", llmClientFactory.getSupportedProviders(),
                "providerCapabilities", capabilities
            );
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to get providers: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/health")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Service health check", description = "Checks the health status of the auto-labeling service")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.debug("Health check requested");
        
        try {
            Map<String, Object> health = Map.of(
                "status", "UP",
                "service", "da-autolabel",
                "timestamp", java.time.LocalDateTime.now(),
                "availableProviders", llmClientFactory.getSupportedProviders().size()
            );
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            log.error("Health check failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "DOWN",
                "error", e.getMessage()
            ));
        }
    }
} 