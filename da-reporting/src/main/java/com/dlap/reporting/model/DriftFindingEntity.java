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

package com.dlap.reporting.model;

import java.time.Instant;
import java.util.UUID;

import com.dlap.reporting.drift.DriftType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Persisted Consumed-stage drift finding (ADR-0040). A durable record of a
 * detected divergence between a PDP decision and an actual asset read, surfaced
 * through the drift query API.
 *
 * <p>{@code readEventId} makes persistence idempotent under Kafka redelivery:
 * a {@code (type, readEventId)} unique constraint means re-processing the same
 * read never double-writes a finding.
 */
@Entity
@Table(name = "drift_finding",
        indexes = {
                @Index(name = "idx_drift_finding_asset", columnList = "asset_id"),
                @Index(name = "idx_drift_finding_subject", columnList = "subject_id"),
                @Index(name = "idx_drift_finding_read_at", columnList = "read_at")
        })
public class DriftFindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "drift_type", nullable = false, length = 40)
    private DriftType driftType;

    @Column(name = "subject_id", nullable = false, length = 512)
    private String subjectId;

    @Column(name = "asset_id", nullable = false, length = 512)
    private String assetId;

    /** The correlated DENY decision id for a bypass; null for an unenforced read. */
    @Column(name = "decision_id", length = 200)
    private String decisionId;

    /** Dedupe key: the {@code eventId} of the read that triggered this finding. */
    @Column(name = "read_event_id", length = 200)
    private String readEventId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @PrePersist
    void onCreate() {
        if (detectedAt == null) {
            detectedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public DriftType getDriftType() {
        return driftType;
    }

    public void setDriftType(DriftType driftType) {
        this.driftType = driftType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(String decisionId) {
        this.decisionId = decisionId;
    }

    public String getReadEventId() {
        return readEventId;
    }

    public void setReadEventId(String readEventId) {
        this.readEventId = readEventId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }
}
