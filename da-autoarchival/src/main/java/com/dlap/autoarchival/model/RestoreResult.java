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

package com.dlap.autoarchival.model;

/**
 * DTO to hold the results of a restore initiation operation.
 */
public class RestoreResult {

    private String restoreJobId;        // Identifier for the restore job (e.g., Glacier Job ID).
    private String statusMessage;       // Initial status or message from the provider (e.g., "Restore initiated").
    private java.time.OffsetDateTime estimatedCompletionTime; // Estimated time when data will be available, if provided.
    private String retrievalTier;       // Tier used for retrieval (e.g., Standard, Expedited, Bulk for Glacier).

    // Constructors
    public RestoreResult() {
    }

    public RestoreResult(String restoreJobId, String statusMessage, java.time.OffsetDateTime estimatedCompletionTime, String retrievalTier) {
        this.restoreJobId = restoreJobId;
        this.statusMessage = statusMessage;
        this.estimatedCompletionTime = estimatedCompletionTime;
        this.retrievalTier = retrievalTier;
    }

    // Getters and Setters
    public String getRestoreJobId() {
        return restoreJobId;
    }

    public void setRestoreJobId(String restoreJobId) {
        this.restoreJobId = restoreJobId;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public java.time.OffsetDateTime getEstimatedCompletionTime() {
        return estimatedCompletionTime;
    }

    public void setEstimatedCompletionTime(java.time.OffsetDateTime estimatedCompletionTime) {
        this.estimatedCompletionTime = estimatedCompletionTime;
    }

    public String getRetrievalTier() {
        return retrievalTier;
    }

    public void setRetrievalTier(String retrievalTier) {
        this.retrievalTier = retrievalTier;
    }

    @Override
    public String toString() {
        return "RestoreResult{" +
                "restoreJobId='" + restoreJobId + '\'' +
                ", statusMessage='" + statusMessage + '\'' +
                ", estimatedCompletionTime=" + estimatedCompletionTime +
                ", retrievalTier='" + retrievalTier + '\'' +
                '}';
    }
} 