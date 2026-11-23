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

package com.dlap.policyengine.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public class PolicyEvaluationRequestDTO {

    @NotBlank
    private String targetAssetId; // The ID of the asset to be evaluated (e.g., from da-catalog)

    // Optional: Additional context or facts that might not be directly part of the asset
    // but are relevant for this specific evaluation.
    private Map<String, Object> evaluationContext;

    /**
     * Optional snapshot from {@code AssetChangeEvent#getAssetSnapshot()} to avoid a catalog round-trip.
     *
     * <p>Held as {@code Map<String, Object>} here for evaluation-time flexibility - the engine
     * already accepts arbitrary facts via {@link #evaluationContext}. Callers wiring from a
     * typed {@code AssetSnapshot} can use a small adapter (see R-P2-3 follow-up): convert
     * the snapshot record to a Map via Jackson's {@code convertValue} or by reading
     * accessors directly. Tracked under R-P2-3 phase-2 if the engine's input shape evolves.
     */
    private Map<String, Object> assetSnapshotFromEvent;

    // Getters and Setters
    public String getTargetAssetId() {
        return targetAssetId;
    }

    public void setTargetAssetId(String targetAssetId) {
        this.targetAssetId = targetAssetId;
    }

    public Map<String, Object> getEvaluationContext() {
        return evaluationContext;
    }

    public void setEvaluationContext(Map<String, Object> evaluationContext) {
        this.evaluationContext = evaluationContext;
    }

    public Map<String, Object> getAssetSnapshotFromEvent() {
        return assetSnapshotFromEvent;
    }

    public void setAssetSnapshotFromEvent(Map<String, Object> assetSnapshotFromEvent) {
        this.assetSnapshotFromEvent = assetSnapshotFromEvent;
    }
} 