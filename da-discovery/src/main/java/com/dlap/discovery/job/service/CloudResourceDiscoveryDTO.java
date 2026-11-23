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

package com.dlap.discovery.job.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dlap.discovery.common.model.CloudResource;

/**
 * Data Transfer Object for cloud resource discovery operations.
 * Used to transfer discovery configuration and results between components
 * in the multi-cloud discovery service.
 */
public class CloudResourceDiscoveryDTO {

    private UUID jobId;
    private String jobName;
    private String cloudProvider;
    private String accountId;
    private List<String> resourceTypeIds;
    private Instant startTime;
    private Instant endTime;
    private String executionMode;
    private Map<String, String> parameters = new HashMap<>();
    private Map<String, String> context = new HashMap<>();
    private List<? extends CloudResource> discoveredResources;
    private String status;
    private String errorMessage;
    private int progressPercentage;
    private String progressMessage;

    public CloudResourceDiscoveryDTO() {
        // Default constructor
    }

    /**
     * Create a new CloudResourceDiscoveryDTO with a generated jobId.
     * 
     * @param cloudProvider   The cloud provider (e.g., "aws", "gcp", "azure")
     * @param resourceTypeIds The IDs of the resource types to discover
     */
    public CloudResourceDiscoveryDTO(String cloudProvider, List<String> resourceTypeIds) {
        this.jobId = UUID.randomUUID();
        this.cloudProvider = cloudProvider;
        this.resourceTypeIds = resourceTypeIds;
        this.startTime = Instant.now();
    }

    // Getters and setters

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getCloudProvider() {
        return cloudProvider;
    }

    public void setCloudProvider(String cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public List<String> getResourceTypeIds() {
        return resourceTypeIds;
    }

    public void setResourceTypeIds(List<String> resourceTypeIds) {
        this.resourceTypeIds = resourceTypeIds;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public void addParameter(String key, String value) {
        this.parameters.put(key, value);
    }

    public Map<String, String> getContext() {
        return context;
    }

    public void setContext(Map<String, String> context) {
        this.context = context;
    }

    public void addContextValue(String key, String value) {
        this.context.put(key, value);
    }

    public List<? extends CloudResource> getDiscoveredResources() {
        return discoveredResources;
    }

    public void setDiscoveredResources(List<? extends CloudResource> discoveredResources) {
        this.discoveredResources = discoveredResources;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(int progressPercentage) {
        this.progressPercentage = progressPercentage;
    }

    public String getProgressMessage() {
        return progressMessage;
    }

    public void setProgressMessage(String progressMessage) {
        this.progressMessage = progressMessage;
    }

    @Override
    public String toString() {
        return "CloudResourceDiscoveryDTO{" +
                "jobId=" + jobId +
                ", jobName='" + jobName + '\'' +
                ", cloudProvider='" + cloudProvider + '\'' +
                ", accountId='" + accountId + '\'' +
                ", resourceTypeIds=" + resourceTypeIds +
                ", status='" + status + '\'' +
                ", progressPercentage=" + progressPercentage +
                '}';
    }

    /**
     * Builder for creating CloudResourceDiscoveryDTO instances.
     */
    public static class Builder {
        private final CloudResourceDiscoveryDTO dto = new CloudResourceDiscoveryDTO();

        public Builder jobId(UUID jobId) {
            dto.setJobId(jobId);
            return this;
        }

        public Builder jobName(String jobName) {
            dto.setJobName(jobName);
            return this;
        }

        public Builder cloudProvider(String cloudProvider) {
            dto.setCloudProvider(cloudProvider);
            return this;
        }

        public Builder accountId(String accountId) {
            dto.setAccountId(accountId);
            return this;
        }

        public Builder resourceTypeIds(List<String> resourceTypeIds) {
            dto.setResourceTypeIds(resourceTypeIds);
            return this;
        }

        public Builder executionMode(String executionMode) {
            dto.setExecutionMode(executionMode);
            return this;
        }

        public Builder parameter(String key, String value) {
            dto.addParameter(key, value);
            return this;
        }

        public Builder parameters(Map<String, String> parameters) {
            dto.setParameters(parameters);
            return this;
        }

        public Builder context(Map<String, String> context) {
            dto.setContext(context);
            return this;
        }

        public Builder startTime(Instant startTime) {
            dto.setStartTime(startTime);
            return this;
        }

        public Builder status(String status) {
            dto.setStatus(status);
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            dto.setErrorMessage(errorMessage);
            return this;
        }

        public Builder progressPercentage(int progressPercentage) {
            dto.setProgressPercentage(progressPercentage);
            return this;
        }

        public Builder progressMessage(String progressMessage) {
            dto.setProgressMessage(progressMessage);
            return this;
        }

        public Builder discoveredResources(List<? extends CloudResource> resources) {
            dto.setDiscoveredResources(resources);
            return this;
        }

        public CloudResourceDiscoveryDTO build() {
            if (dto.getJobId() == null) {
                dto.setJobId(UUID.randomUUID());
            }
            if (dto.getStartTime() == null) {
                dto.setStartTime(Instant.now());
            }
            return dto;
        }
    }
}