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

package com.dlap.policyengine.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of the catalog activity report ({@code GET /api/v1/catalog/assets/activity}, CAT-02b),
 * the subset the scheduled policy sweep evaluates (POL-01). Field names match the catalog's
 * {@code AssetActivityRowDTO}; enums arrive as their names. Unknown values are null.
 *
 * @param assetId            catalog asset id
 * @param assetUri           canonical asset URI
 * @param assetName          display name
 * @param cloudProvider      cloud provider
 * @param serviceType        asset kind
 * @param assetType          asset type
 * @param region             region or location
 * @param status             lifecycle status
 * @param cloudCreatedAt     provider creation time; null when unknown
 * @param sizeBytes          latest observed size; null when unknown
 * @param lastAccessedAt     latest recorded read (event time); null when none is recorded
 * @param readCoverage       read-logging coverage: FULL, PARTIAL, NONE, or null when unknown
 * @param lastModifiedAt     latest recorded content write
 * @param retentionStatus    stored retention status
 * @param retentionExpiresAt expiry found by the last retention evaluation
 * @param legalHold          DLAP legal hold
 * @param labels             label names; empty when the asset has none
 * @param asOf               the instant the catalog computed the row against
 * @param readCoverageObservedAt when the catalog last observed {@code readCoverage}, as the
 *                           ISO-8601 text the catalog sends; null when the catalog does not send it
 *                           (it is added by COV-02, Q-475). Kept as text so that an unexpected value
 *                           never fails a page; {@code AssetSnapshots} parses it.
 * @param readCoverageSince  start of the current unbroken run of {@code FULL} coverage, as the
 *                           ISO-8601 text the catalog sends (COV-02); null when unknown or when
 *                           coverage is not {@code FULL}. Read as text for the same reason (POL-03,
 *                           Q-434).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogActivityRowDTO(
        UUID assetId,
        String assetUri,
        String assetName,
        String cloudProvider,
        String serviceType,
        String assetType,
        String region,
        String status,
        Instant cloudCreatedAt,
        Long sizeBytes,
        Instant lastAccessedAt,
        String readCoverage,
        Instant lastModifiedAt,
        String retentionStatus,
        Instant retentionExpiresAt,
        Boolean legalHold,
        List<String> labels,
        Instant asOf,
        String readCoverageObservedAt,
        String readCoverageSince) {

    /**
     * A row without the coverage start (the activity report before COV-02 sent it).
     *
     * @param assetId            catalog asset id
     * @param assetUri           canonical asset URI
     * @param assetName          display name
     * @param cloudProvider      cloud provider
     * @param serviceType        asset kind
     * @param assetType          asset type
     * @param region             region or location
     * @param status             lifecycle status
     * @param cloudCreatedAt     provider creation time
     * @param sizeBytes          latest observed size
     * @param lastAccessedAt     latest recorded read
     * @param readCoverage       read-logging coverage
     * @param lastModifiedAt     latest recorded content write
     * @param retentionStatus    stored retention status
     * @param retentionExpiresAt expiry found by the last retention evaluation
     * @param legalHold          DLAP legal hold
     * @param labels             label names
     * @param asOf               the instant the catalog computed the row against
     * @param readCoverageObservedAt when the catalog last observed {@code readCoverage}
     */
    public CatalogActivityRowDTO(UUID assetId, String assetUri, String assetName, String cloudProvider,
            String serviceType, String assetType, String region, String status, Instant cloudCreatedAt,
            Long sizeBytes, Instant lastAccessedAt, String readCoverage, Instant lastModifiedAt,
            String retentionStatus, Instant retentionExpiresAt, Boolean legalHold, List<String> labels,
            Instant asOf, String readCoverageObservedAt) {
        this(assetId, assetUri, assetName, cloudProvider, serviceType, assetType, region, status, cloudCreatedAt,
                sizeBytes, lastAccessedAt, readCoverage, lastModifiedAt, retentionStatus, retentionExpiresAt,
                legalHold, labels, asOf, readCoverageObservedAt, null);
    }

    /**
     * A row without the coverage observation time (the activity report before COV-02).
     *
     * @param assetId            catalog asset id
     * @param assetUri           canonical asset URI
     * @param assetName          display name
     * @param cloudProvider      cloud provider
     * @param serviceType        asset kind
     * @param assetType          asset type
     * @param region             region or location
     * @param status             lifecycle status
     * @param cloudCreatedAt     provider creation time
     * @param sizeBytes          latest observed size
     * @param lastAccessedAt     latest recorded read
     * @param readCoverage       read-logging coverage
     * @param lastModifiedAt     latest recorded content write
     * @param retentionStatus    stored retention status
     * @param retentionExpiresAt expiry found by the last retention evaluation
     * @param legalHold          DLAP legal hold
     * @param labels             label names
     * @param asOf               the instant the catalog computed the row against
     */
    public CatalogActivityRowDTO(UUID assetId, String assetUri, String assetName, String cloudProvider,
            String serviceType, String assetType, String region, String status, Instant cloudCreatedAt,
            Long sizeBytes, Instant lastAccessedAt, String readCoverage, Instant lastModifiedAt,
            String retentionStatus, Instant retentionExpiresAt, Boolean legalHold, List<String> labels,
            Instant asOf) {
        this(assetId, assetUri, assetName, cloudProvider, serviceType, assetType, region, status, cloudCreatedAt,
                sizeBytes, lastAccessedAt, readCoverage, lastModifiedAt, retentionStatus, retentionExpiresAt,
                legalHold, labels, asOf, null, null);
    }
}
