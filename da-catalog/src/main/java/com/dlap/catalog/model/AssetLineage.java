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

package com.dlap.catalog.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dlap.common.model.entity.Asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "dlap_asset_lineage", schema = "dlap_catalog",
    indexes = {
        @Index(name = "idx_lineage_upstream_asset_id", columnList = "upstream_asset_id"),
        @Index(name = "idx_lineage_downstream_asset_id", columnList = "downstream_asset_id")
    })
public class AssetLineage {

    @Id
    private UUID lineageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "upstream_asset_id", nullable = false)
    private Asset upstreamAsset;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "downstream_asset_id", nullable = false)
    private Asset downstreamAsset;

    @Column(length = 100)
    private String relationshipType; // e.g., "TRANSFORMED_FROM", "COPIED_FROM", "VIEW_OF"

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> detailsJson; // e.g., job name, transformation logic snippet

    private UUID createdByUserId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public AssetLineage() {
        this.lineageId = UUID.randomUUID();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // Getters and Setters
    public UUID getLineageId() {
        return lineageId;
    }

    public void setLineageId(UUID lineageId) {
        this.lineageId = lineageId;
    }

    public Asset getUpstreamAsset() {
        return upstreamAsset;
    }

    public void setUpstreamAsset(Asset upstreamAsset) {
        this.upstreamAsset = upstreamAsset;
    }

    public Asset getDownstreamAsset() {
        return downstreamAsset;
    }

    public void setDownstreamAsset(Asset downstreamAsset) {
        this.downstreamAsset = downstreamAsset;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public void setRelationshipType(String relationshipType) {
        this.relationshipType = relationshipType;
    }

    public Map<String, Object> getDetailsJson() {
        return detailsJson;
    }

    public void setDetailsJson(Map<String, Object> detailsJson) {
        this.detailsJson = detailsJson;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
} 