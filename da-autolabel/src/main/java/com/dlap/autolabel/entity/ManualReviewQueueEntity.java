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

package com.dlap.autolabel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sub-threshold label suggestion held for human review.
 *
 * <p>M4 / R-P1-2 follow-up. When the auto-label pipeline calls the
 * configured {@code ILLMClient} and gets back a {@code LabelSuggestion}
 * with {@code confidence < dlap.autolabel.confidence-threshold}
 * (default 0.7), the suggestion is queued here instead of being applied
 * automatically. A reviewer (UI in M7) approves or rejects each row.
 */
@Entity
@Table(
    name = "manual_review_queue",
    indexes = {
        @Index(name = "idx_mrq_asset_id", columnList = "asset_id"),
        @Index(name = "idx_mrq_status", columnList = "status"),
        @Index(name = "idx_mrq_created_at", columnList = "created_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualReviewQueueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "asset_id", columnDefinition = "UUID", nullable = false)
    private UUID assetId;

    @Column(name = "label", length = 255, nullable = false)
    private String label;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "confidence", nullable = false)
    private Double confidence;

    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "llm_provider", length = 64)
    private String llmProvider;

    /**
     * One of: {@code PENDING}, {@code APPROVED}, {@code REJECTED}.
     * UI sets the terminal state; the auto-label orchestrator only
     * inserts {@code PENDING} rows.
     */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "reviewer_user_id", columnDefinition = "UUID")
    private UUID reviewerUserId;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;
}
