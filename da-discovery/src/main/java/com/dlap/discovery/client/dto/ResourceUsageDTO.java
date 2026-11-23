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

import java.util.List;

/**
 * Data Transfer Object for resource usage history.
 */
public class ResourceUsageDTO {
    private String resourceId;
    private List<MetricPointDTO> usageHistory;
    private ResourceSummaryDTO summary;
    
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public List<MetricPointDTO> getUsageHistory() {
        return usageHistory;
    }
    
    public void setUsageHistory(List<MetricPointDTO> usageHistory) {
        this.usageHistory = usageHistory;
    }
    
    public ResourceSummaryDTO getSummary() {
        return summary;
    }
    
    public void setSummary(ResourceSummaryDTO summary) {
        this.summary = summary;
    }
    
    @Override
    public String toString() {
        return String.format("ResourceUsageDTO{resourceId='%s', historySize=%d}", 
            resourceId, usageHistory != null ? usageHistory.size() : 0);
    }
}