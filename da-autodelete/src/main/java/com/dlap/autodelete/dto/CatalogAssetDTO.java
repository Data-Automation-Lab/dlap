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
package com.dlap.autodelete.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Read-only subset of {@code da-catalog}'s {@code AssetOutputDTO} consumed by
 * da-autodelete via Feign.
 *
 * <p>Only the fields needed to route a deletion SDK call are declared; all
 * other catalog fields are ignored by Jackson. The critical fields are:
 * <ul>
 *   <li>{@link #cloudProvider} - routes to the right {@code ICloudDeletionService}.
 *   <li>{@link #assetUri} - the canonical cloud locator (e.g. {@code gs://bucket/key},
 *       {@code bq://project/dataset/table}) that the deletion SDK operates on.
 *   <li>{@link #cloudConnectionId} - recorded in audit rows and available for
 *       future per-connection credential routing.
 * </ul>
 *
 * <p>Keeping {@code cloudProvider} on this DTO (rather than making a second
 * Feign hop to a connection-lookup endpoint) keeps the asset-resolution path
 * to a single round-trip to da-catalog.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogAssetDTO {

    private UUID assetId;
    private String assetName;
    private String assetType;
    private UUID cloudConnectionId;
    private String cloudProvider;
    private String assetUri;
    private String nativeAssetId;
    private String region;
    /** Asset kind, for example {@code GCS_BUCKET} (RET-02 picks the native rule by it). */
    private String serviceType;
    /** Legal hold (ADR-0046); null when the catalog did not say, which the executor treats as held. */
    private Boolean legalHold;
    /** Read coverage (COV-01): {@code FULL}, {@code PARTIAL} or {@code NONE}; null when unknown. */
    private String readCoverage;
    /** Why the coverage is what it is. */
    private java.util.List<String> readCoverageReasons;
    /** Latest observed size in bytes (null when unknown); the storage-reclaimed KPI sums it (Q-619). */
    private Long sizeBytes;

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
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

    public String getAssetUri() {
        return assetUri;
    }

    public void setAssetUri(String assetUri) {
        this.assetUri = assetUri;
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

    /** @return the asset kind */
    public String getServiceType() {
        return serviceType;
    }

    /** @param serviceType the asset kind */
    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    /** @return the legal hold, or null when the catalog did not say */
    public Boolean getLegalHold() {
        return legalHold;
    }

    /** @param legalHold the legal hold */
    public void setLegalHold(Boolean legalHold) {
        this.legalHold = legalHold;
    }

    /** @return the read coverage, or null */
    public String getReadCoverage() {
        return readCoverage;
    }

    /** @param readCoverage the read coverage */
    public void setReadCoverage(String readCoverage) {
        this.readCoverage = readCoverage;
    }

    /** @return the coverage reasons, or null */
    public java.util.List<String> getReadCoverageReasons() {
        return readCoverageReasons;
    }

    /** @param readCoverageReasons the coverage reasons */
    public void setReadCoverageReasons(java.util.List<String> readCoverageReasons) {
        this.readCoverageReasons = readCoverageReasons;
    }
}
