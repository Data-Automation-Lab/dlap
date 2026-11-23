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

package com.dlap.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Asset change envelope published on {@code dlap.asset.change.event}.
 *
 * <p>Two event flavours share this envelope:
 * <ul>
 *   <li>Lifecycle: {@code ASSET_CREATED}, {@code ASSET_UPDATED},
 *       {@code ASSET_DELETED} - carries an {@link AssetSnapshot} so
 *       downstream services (policy engine, reporting) can read
 *       identity/classification without a catalog round-trip.</li>
 *   <li>Label changes: {@code LABELS_ASSIGNED}, {@code LABEL_REMOVED}
 *       - carries an {@link AssetSnapshot} (so consumers still know
 *       what asset this is) plus a {@link LabelAssignmentDetails}
 *       payload with the label-specific keys.</li>
 * </ul>
 *
 * <p>Wire format change: v0.2.0 N0 (R-P2-3) replaces the previous
 * {@code Map<String, Object> assetData} / {@code previousAssetData}
 * fields with the strongly-typed {@link #assetSnapshot},
 * {@link #previousAssetSnapshot}, {@link #labelDetails} below. The
 * Map fields had a single producer in {@code da-catalog/AssetService}
 * and zero runtime readers (only a Javadoc pointer), so the break is
 * safe. Consumers that ignored the Map keep working unchanged.
 *
 * <p>Consumers that DID parse the Map (none in-tree at v0.1.0) must
 * switch to {@link #getAssetSnapshot()} accessors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetChangeEvent {

    /**
     * The type of change that occurred to the asset.
     * Lifecycle: {@code ASSET_CREATED}, {@code ASSET_UPDATED}, {@code ASSET_DELETED}.
     * Labels: {@code LABELS_ASSIGNED}, {@code LABEL_REMOVED}.
     */
    private String eventType;

    /** The unique identifier of the asset that changed (UUID string). */
    private String assetId;

    /** Snapshot of the asset's user-visible fields after the change. */
    private AssetSnapshot assetSnapshot;

    /** Previous snapshot (lifecycle UPDATE only; null otherwise). */
    private AssetSnapshot previousAssetSnapshot;

    /** Label-specific change details (label events only; null for lifecycle). */
    private LabelAssignmentDetails labelDetails;

    /** Timestamp when the event occurred (ISO 8601 format). */
    private String timestamp;

    /** ID of the user who triggered the change (null for system actions). */
    private String userId;

    /** Source service that generated the event (e.g. {@code da-catalog}). */
    private String sourceService;

    public AssetChangeEvent() {
    }

    public AssetChangeEvent(String eventType, String assetId, AssetSnapshot assetSnapshot) {
        this.eventType = eventType;
        this.assetId = assetId;
        this.assetSnapshot = assetSnapshot;
    }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }

    public AssetSnapshot getAssetSnapshot() { return assetSnapshot; }
    public void setAssetSnapshot(AssetSnapshot assetSnapshot) { this.assetSnapshot = assetSnapshot; }

    public AssetSnapshot getPreviousAssetSnapshot() { return previousAssetSnapshot; }
    public void setPreviousAssetSnapshot(AssetSnapshot previousAssetSnapshot) { this.previousAssetSnapshot = previousAssetSnapshot; }

    public LabelAssignmentDetails getLabelDetails() { return labelDetails; }
    public void setLabelDetails(LabelAssignmentDetails labelDetails) { this.labelDetails = labelDetails; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSourceService() { return sourceService; }
    public void setSourceService(String sourceService) { this.sourceService = sourceService; }
}
