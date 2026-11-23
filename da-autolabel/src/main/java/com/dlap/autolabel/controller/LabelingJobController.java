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

import com.dlap.autolabel.client.rest.dto.LabelingFeedbackRequest;
import com.dlap.autolabel.client.rest.dto.LabelingFeedbackResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobListResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobRequest;
import com.dlap.autolabel.client.rest.dto.LabelingJobResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobStatusResponse;
import com.dlap.autolabel.exception.AutoLabelingException;
import com.dlap.autolabel.service.ILabelingJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/labeling") // Changed base path to /api/v1/labeling
@RequiredArgsConstructor
@Slf4j
public class LabelingJobController {

    private final ILabelingJobService labelingJobService;

    /**
     * Submits a new auto-labeling job.
     * Requires DATA_STEWARD or ADMIN role.
     *
     * @param jobRequest The labeling job request.
     * @return ResponseEntity with LabelingJobResponse or an error.
     */
    @PostMapping("/jobs")
    @PreAuthorize("hasAnyAuthority('ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_ADMIN')")
    public ResponseEntity<LabelingJobResponse> submitLabelingJob(@Valid @RequestBody LabelingJobRequest jobRequest) {
        log.info("Received request to submit labeling job: {}", jobRequest.getJobName());
        try {
            LabelingJobResponse jobResponse = labelingJobService.submitLabelingJob(jobRequest);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobResponse);
        } catch (AutoLabelingException e) {
            log.error("Error submitting labeling job '{}': {}", jobRequest.getJobName(), e.getMessage());
            return ResponseEntity.badRequest().body(LabelingJobResponse.builder().message(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Unexpected error submitting labeling job '{}': {}", jobRequest.getJobName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(LabelingJobResponse.builder().message("An unexpected error occurred.").build());
        }
    }

    /**
     * Retrieves the status of a specific auto-labeling job.
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @param jobId The ID of the job.
     * @return ResponseEntity with LabelingJobStatusResponse or an error.
     */
    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("hasAnyAuthority('ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<LabelingJobStatusResponse> getJobStatus(@PathVariable String jobId) {
        log.info("Received request to get status for labeling job ID: {}", jobId);
        LabelingJobStatusResponse statusResponse = labelingJobService.getJobStatus(jobId);
        if (statusResponse != null) {
            return ResponseEntity.ok(statusResponse);
        } else {
            log.warn("Labeling job with ID '{}' not found.", jobId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Lists all auto-labeling jobs with pagination.
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @param pageable Pagination information.
     * @return ResponseEntity with LabelingJobListResponse or an error.
     */
    @GetMapping("/jobs")
    @PreAuthorize("hasAnyAuthority('ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account (da-admin)
    public ResponseEntity<LabelingJobListResponse> listJobs(@PageableDefault(size = 20) Pageable pageable) {
        log.info("Received request to list labeling jobs. Pagination: {}", pageable);
        try {
            LabelingJobListResponse listResponse = labelingJobService.listJobs(pageable);
            return ResponseEntity.ok(listResponse);
        } catch (Exception e) {
            log.error("Error listing labeling jobs: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Submits feedback for auto-suggested labels on an asset.
     * Requires DATA_STEWARD or ADMIN role.
     *
     * @param feedbackRequest The labeling feedback request.
     * @return ResponseEntity with LabelingFeedbackResponse or an error.
     */
    @PostMapping("/feedback")
    @PreAuthorize("hasAnyAuthority('ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_ADMIN')")
    public ResponseEntity<LabelingFeedbackResponse> submitLabelingFeedback(@Valid @RequestBody LabelingFeedbackRequest feedbackRequest) {
        log.info("Received labeling feedback for asset ID: {}", feedbackRequest.getAssetId());
        try {
            LabelingFeedbackResponse feedbackResponse = labelingJobService.processLabelingFeedback(feedbackRequest);
            return ResponseEntity.ok(feedbackResponse);
        } catch (AutoLabelingException e) {
            log.error("Error processing labeling feedback for asset '{}': {}", feedbackRequest.getAssetId(), e.getMessage());
            return ResponseEntity.badRequest().body(LabelingFeedbackResponse.builder().message(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Unexpected error processing labeling feedback for asset '{}': {}", feedbackRequest.getAssetId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(LabelingFeedbackResponse.builder().message("An unexpected error occurred while processing feedback.").build());
        }
    }
} 