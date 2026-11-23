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

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Data Transfer Object for resource metrics.
 */
public class ResourceMetricsDTO {
    private String resourceId;
    private long accessCount;
    private ZonedDateTime lastAccessTime;
    private long sizeBytes;
    private Map<String, Double> customMetrics = new HashMap<>();
    
    // Getters and setters
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public long getAccessCount() {
        return accessCount;
    }
    
    public void setAccessCount(long accessCount) {
        this.accessCount = accessCount;
    }
    
    public ZonedDateTime getLastAccessTime() {
        return lastAccessTime;
    }
    
    public void setLastAccessTime(ZonedDateTime lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }
    
    public long getSizeBytes() {
        return sizeBytes;
    }
    
    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
    
    public Map<String, Double> getCustomMetrics() {
        return customMetrics;
    }
    
    public void setCustomMetrics(Map<String, Double> customMetrics) {
        this.customMetrics = customMetrics != null ? customMetrics : new HashMap<>();
    }
    
    @Override
    public String toString() {
        return String.format("ResourceMetricsDTO{resourceId='%s', accessCount=%d}", 
            resourceId, accessCount);
    }
} 