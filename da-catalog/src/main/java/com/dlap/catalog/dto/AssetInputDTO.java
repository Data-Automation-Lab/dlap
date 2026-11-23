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

package com.dlap.catalog.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class AssetInputDTO {

    @NotBlank(message = "Asset name is required")
    @Size(max = 255)
    private String assetName;

    @NotNull(message = "Cloud Connection ID is required")
    private UUID cloudConnectionId;

    @NotBlank(message = "Cloud provider is required")
    private String cloudProvider; // e.g., GCP, AWS, AZURE, OCI

    @NotBlank(message = "Asset type is required")
    private String assetType; // e.g., "BigQuery Dataset", "S3 Bucket"

    @NotBlank(message = "Native asset ID is required")
    @Size(max = 1024)
    private String nativeAssetId;

    @Size(max = 255)
    private String region;

    private Map<String, Object> detailsJson; // Provider-specific metadata

    // Optional: initial set of labels to apply
    private List<String> labels; 

    // createdByUserId will be set by the system/admin context, not directly from DTO
    // discoveredAt, lastScannedAt will be set by the system

    // Getters and Setters
    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public UUID getCloudConnectionId() {
        return cloudConnectionId;
    }

    public void setCloudConnectionId(UUID cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    public String getCloudProvider() {
        return cloudProvider;
    }

    public void setCloudProvider(String cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public String getNativeAssetId() {
        return nativeAssetId;
    }

    public void setNativeAssetId(String nativeAssetId) {
        this.nativeAssetId = nativeAssetId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, Object> getDetailsJson() {
        return detailsJson;
    }

    public void setDetailsJson(Map<String, Object> detailsJson) {
        this.detailsJson = detailsJson;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }
} 