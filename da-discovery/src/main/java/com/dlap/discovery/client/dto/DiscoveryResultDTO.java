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

package com.dlap.discovery.client.dto;

import java.util.ArrayList;
import java.util.List;
import java.time.ZonedDateTime;

/**
 * DTO containing the results of a discovery operation.
 */
public class DiscoveryResultDTO {
    private String projectId;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private long durationMs;
    private List<ResourceDTO> discoveredResources = new ArrayList<>();
    private List<String> storageBuckets = new ArrayList<>();
    private List<DatasetDTO> datasets = new ArrayList<>();
    private int errorCount;
    private String summary;
    // Getters and setters

    public String getSummary() {
        return summary;
    }

    public String getProjectId() {
        return projectId;
    }
    
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
    
    public ZonedDateTime getStartTime() {
        return startTime;
    }
    
    public void setStartTime(ZonedDateTime startTime) {
        this.startTime = startTime;
    }
    
    public ZonedDateTime getEndTime() {
        return endTime;
    }
    
    public void setEndTime(ZonedDateTime endTime) {
        this.endTime = endTime;
    }
    
    public long getDurationMs() {
        return durationMs;
    }
    
    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
    
    public List<ResourceDTO> getDiscoveredResources() {
        return discoveredResources;
    }
    
    public void setDiscoveredResources(List<ResourceDTO> discoveredResources) {
        this.discoveredResources = discoveredResources != null ? 
            discoveredResources : new ArrayList<>();
    }
    
    public List<String> getStorageBuckets() {
        return storageBuckets;
    }
    
    public void setStorageBuckets(List<String> storageBuckets) {
        this.storageBuckets = storageBuckets != null ? 
            storageBuckets : new ArrayList<>();
    }
    
    public List<DatasetDTO> getDatasets() {
        return datasets;
    }
    
    public void setDatasets(List<DatasetDTO> datasets) {
        this.datasets = datasets != null ? 
            datasets : new ArrayList<>();
    }
    
    public int getErrorCount() {
        return errorCount;
    }
    
    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public void setSummary(String string) {
        this.summary = string;
    }
} 