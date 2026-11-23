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

/**
 * Data Transfer Object for resource access counters.
 */
public class AccessCounterDTO {
    private String resourceId;
    private String resourceType;
    private int accessCount;
    private ZonedDateTime lastAccessed;
    private ZonedDateTime firstAccessed;
    
    // Getters and setters
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public String getResourceType() {
        return resourceType;
    }
    
    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
    
    public int getAccessCount() {
        return accessCount;
    }
    
    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }
    
    public ZonedDateTime getLastAccessed() {
        return lastAccessed;
    }
    
    public void setLastAccessed(ZonedDateTime lastAccessed) {
        this.lastAccessed = lastAccessed;
    }
    
    public ZonedDateTime getFirstAccessed() {
        return firstAccessed;
    }
    
    public void setFirstAccessed(ZonedDateTime firstAccessed) {
        this.firstAccessed = firstAccessed;
    }
    
    /**
     * Increments the access count by one.
     */
    public void incrementAccessCount() {
        this.accessCount++;
    }
    
    @Override
    public String toString() {
        return String.format("AccessCounterDTO{resourceId='%s', resourceType='%s', accessCount=%d}", 
            resourceId, resourceType, accessCount);
    }
} 