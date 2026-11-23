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

package com.dlap.policyengine.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;

@Entity
@Table(name = "policy_evaluations")
public class PolicyEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID policyId;

    @Column(nullable = false, length = 255) // External asset ID from da-catalog or other source
    private String targetAssetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyEvaluationStatus status;

    // Store details about the evaluation, e.g., which rules passed/failed, input facts snapshot
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> evaluationDetails;

    // Store actions that were triggered as a result of this evaluation
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> triggeredActions;

    @Column(nullable = false)
    private Instant evaluatedAt;

    @Column(columnDefinition = "UUID")
    private UUID evaluationTriggeredByUserId; // Optional: if triggered by a user action

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public void setPolicyId(UUID policyId) {
        this.policyId = policyId;
    }

    public String getTargetAssetId() {
        return targetAssetId;
    }

    public void setTargetAssetId(String targetAssetId) {
        this.targetAssetId = targetAssetId;
    }

    public PolicyEvaluationStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyEvaluationStatus status) {
        this.status = status;
    }

    public Map<String, Object> getEvaluationDetails() {
        return evaluationDetails;
    }

    public void setEvaluationDetails(Map<String, Object> evaluationDetails) {
        this.evaluationDetails = evaluationDetails;
    }

    public Map<String, Object> getTriggeredActions() {
        return triggeredActions;
    }

    public void setTriggeredActions(Map<String, Object> triggeredActions) {
        this.triggeredActions = triggeredActions;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public UUID getEvaluationTriggeredByUserId() {
        return evaluationTriggeredByUserId;
    }

    public void setEvaluationTriggeredByUserId(UUID evaluationTriggeredByUserId) {
        this.evaluationTriggeredByUserId = evaluationTriggeredByUserId;
    }

    @PrePersist
    protected void onPersist() {
        if (evaluatedAt == null) {
            evaluatedAt = Instant.now();
        }
    }
} 