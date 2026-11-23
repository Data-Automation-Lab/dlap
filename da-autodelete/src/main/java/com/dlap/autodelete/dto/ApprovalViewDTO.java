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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The fields of the policy engine's {@code ApprovalView} (docs/specs/approvals.md section 8) the
 * executor reads; other fields are ignored.
 *
 * @param id                the request id
 * @param status            {@code PENDING}, {@code APPROVED}, {@code APPROVED_BREAK_GLASS}, {@code REJECTED} or {@code EXPIRED}
 * @param executable        whether an executor may act on it now
 * @param origin            {@code POLICY} or {@code API}
 * @param actionClass       the action class
 * @param actionType        the action type
 * @param assetId           the catalog asset id
 * @param assetUri          the canonical asset URI
 * @param fireId            the fire id of a policy fire
 * @param requestedBy       who asked
 * @param submittedBy       who sent it
 * @param excludedSubjects  subjects that may never decide it
 * @param requiredApprovals distinct approvals needed
 * @param approvals         approvals so far
 * @param resolvedAt        when it reached its terminal state
 * @param decisions         the decisions, in order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalViewDTO(
        UUID id,
        String status,
        boolean executable,
        String origin,
        String actionClass,
        String actionType,
        UUID assetId,
        String assetUri,
        String fireId,
        String requestedBy,
        String submittedBy,
        List<String> excludedSubjects,
        int requiredApprovals,
        int approvals,
        Instant resolvedAt,
        List<Decision> decisions) {

    /**
     * One decision.
     *
     * @param approverSub the decider's JWT subject
     * @param decision    {@code APPROVE}, {@code REJECT} or {@code BREAK_GLASS}
     * @param decidedAt   when
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(String approverSub, String decision, Instant decidedAt) {
    }
}
