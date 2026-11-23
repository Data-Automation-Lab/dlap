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
 * DTO summarizing resource usage statistics.
 */
public class ResourceSummaryDTO {
    private long totalAccesses;
    private double averageUsage;
    private ZonedDateTime firstAccess;
    private ZonedDateTime lastAccess;
    
    // Getters and setters
    public long getTotalAccesses() {
        return totalAccesses;
    }
    
    public void setTotalAccesses(long totalAccesses) {
        this.totalAccesses = totalAccesses;
    }
    
    public double getAverageUsage() {
        return averageUsage;
    }
    
    public void setAverageUsage(double averageUsage) {
        this.averageUsage = averageUsage;
    }
    
    public ZonedDateTime getFirstAccess() {
        return firstAccess;
    }
    
    public void setFirstAccess(ZonedDateTime firstAccess) {
        this.firstAccess = firstAccess;
    }
    
    public ZonedDateTime getLastAccess() {
        return lastAccess;
    }
    
    public void setLastAccess(ZonedDateTime lastAccess) {
        this.lastAccess = lastAccess;
    }
    
    @Override
    public String toString() {
        return String.format("ResourceSummaryDTO{totalAccesses=%d, averageUsage=%.2f}", 
            totalAccesses, averageUsage);
    }
} 