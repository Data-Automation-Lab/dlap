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

package com.dlap.discovery.client.rest.dto;

import com.dlap.discovery.job.JobStatus; // Assuming JobStatus is the enum for job statuses
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL) // Exclude null fields from JSON output
public class DiscoveryScanJobResponse {

    private String scanId;
    private String scanName;
    private String cloudConnectionId;
    private DiscoveryScanRequest.ScanType scanType; // Reusing the enum from DiscoveryScanRequest
    private JobStatus status;
    private DiscoveryScanRequest.Scope scope; // Reusing Scope DTO from DiscoveryScanRequest for structure
    private Instant submittedAt;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private ProgressInfo progress;
    private ScanSummary summary;
    private String resultLocation; // e.g., "/api/v1/discovery/scans/{scanId}/results"

    // Getters and Setters
    public String getScanId() {
        return scanId;
    }

    public void setScanId(String scanId) {
        this.scanId = scanId;
    }

    public String getScanName() {
        return scanName;
    }

    public void setScanName(String scanName) {
        this.scanName = scanName;
    }

    public String getCloudConnectionId() {
        return cloudConnectionId;
    }

    public void setCloudConnectionId(String cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    public DiscoveryScanRequest.ScanType getScanType() {
        return scanType;
    }

    public void setScanType(DiscoveryScanRequest.ScanType scanType) {
        this.scanType = scanType;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public DiscoveryScanRequest.Scope getScope() {
        return scope;
    }

    public void setScope(DiscoveryScanRequest.Scope scope) {
        this.scope = scope;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public ProgressInfo getProgress() {
        return progress;
    }

    public void setProgress(ProgressInfo progress) {
        this.progress = progress;
    }

    public ScanSummary getSummary() {
        return summary;
    }

    public void setSummary(ScanSummary summary) {
        this.summary = summary;
    }

    public String getResultLocation() {
        return resultLocation;
    }

    public void setResultLocation(String resultLocation) {
        this.resultLocation = resultLocation;
    }

    // Nested DTO for ProgressInfo
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProgressInfo {

        private Integer percentage;
        private String currentStep;
        private Integer totalSteps;

        public ProgressInfo(Integer percentage, String currentStep, Integer totalSteps) {
            this.percentage = percentage;
            this.currentStep = currentStep;
            this.totalSteps = totalSteps;
        }

        // Getters and Setters
        public Integer getPercentage() {
            return percentage;
        }

        public void setPercentage(Integer percentage) {
            this.percentage = percentage;
        }

        public String getCurrentStep() {
            return currentStep;
        }

        public void setCurrentStep(String currentStep) {
            this.currentStep = currentStep;
        }

        public Integer getTotalSteps() {
            return totalSteps;
        }

        public void setTotalSteps(Integer totalSteps) {
            this.totalSteps = totalSteps;
        }
    }

    // Nested DTO for ScanSummary
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScanSummary {

        private Long assetsFound;
        private Long newAssets;
        private Integer errors;

        public ScanSummary(Long assetsFound, Long newAssets, Integer errors) {
            this.assetsFound = assetsFound;
            this.newAssets = newAssets;
            this.errors = errors;
        }

        // Getters and Setters
        public Long getAssetsFound() {
            return assetsFound;
        }

        public void setAssetsFound(Long assetsFound) {
            this.assetsFound = assetsFound;
        }

        public Long getNewAssets() {
            return newAssets;
        }

        public void setNewAssets(Long newAssets) {
            this.newAssets = newAssets;
        }

        public Integer getErrors() {
            return errors;
        }

        public void setErrors(Integer errors) {
            this.errors = errors;
        }
    }
}
