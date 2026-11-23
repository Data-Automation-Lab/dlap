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

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/autodelete/would-delete} (RET-02).
 *
 * @param recordId              the soft-delete record
 * @param assetId               the catalog asset id
 * @param assetUri              the canonical asset URI
 * @param cloudProvider         the provider
 * @param serviceType           the asset kind
 * @param status                the record's state
 * @param origin                {@code POLICY} or {@code TASK}
 * @param policyId              the policy, for a policy delete
 * @param queuedAt              when the window started
 * @param scheduledHardDeleteAt when the window ends
 * @param windowDays            the window, after the floor
 * @param approvalId            the approval of the soft delete
 * @param hardDeleteApprovalId  the HARD_DELETE approval, once requested
 * @param readCoverage          the read coverage when the window started
 * @param plannedMode           {@code NATIVE_RULE}, {@code DIRECT} or {@code NONE}
 * @param plannedPlatform       the native platform or {@code ENGINE_<provider>}
 * @param ruleId                the native rule name
 * @param ruleDocument          the native rule in the provider's API shape, or the engine location
 * @param ruleEffectiveAt       when the native rule takes effect
 * @param dispositionStatus     the disposition's state once the executor acted, else null
 * @param dryRun                whether the executor runs in dry-run mode
 * @param lastError             the last error or refusal, with its machine reason first
 */
public record WouldDeleteRowDTO(
        UUID recordId,
        UUID assetId,
        String assetUri,
        String cloudProvider,
        String serviceType,
        String status,
        String origin,
        String policyId,
        Instant queuedAt,
        Instant scheduledHardDeleteAt,
        Integer windowDays,
        UUID approvalId,
        UUID hardDeleteApprovalId,
        String readCoverage,
        String plannedMode,
        String plannedPlatform,
        String ruleId,
        String ruleDocument,
        Instant ruleEffectiveAt,
        String dispositionStatus,
        boolean dryRun,
        String lastError) {
}
