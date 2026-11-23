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

package com.dlap.reporting.dto;

import java.time.Instant;
import java.util.UUID;

import com.dlap.reporting.drift.DriftType;
import com.dlap.reporting.model.DriftFindingEntity;

/**
 * API response shape for a single drift finding.
 *
 * @param id         finding id
 * @param type       {@code UNENFORCED_READ} | {@code ENFORCEMENT_BYPASS}
 * @param subjectId  the principal that read the asset
 * @param assetId    the asset that was read
 * @param decisionId correlated DENY decision id (bypass) or {@code null}
 * @param readAt     when the offending read occurred
 * @param detail     human-readable explanation
 * @param detectedAt when this finding was recorded
 */
public record DriftFindingDTO(
        UUID id,
        DriftType type,
        String subjectId,
        String assetId,
        String decisionId,
        Instant readAt,
        String detail,
        Instant detectedAt) {

    public static DriftFindingDTO from(DriftFindingEntity e) {
        return new DriftFindingDTO(
                e.getId(),
                e.getDriftType(),
                e.getSubjectId(),
                e.getAssetId(),
                e.getDecisionId(),
                e.getReadAt(),
                e.getDetail(),
                e.getDetectedAt());
    }
}
