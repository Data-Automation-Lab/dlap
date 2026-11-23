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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.dto.ApprovalViewDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test builders for the policy engine's approval view (docs/specs/approvals.md section 8).
 */
final class Approvals {

    static final String ALICE = "7d0e6c55-0000-4000-8000-0000000a11ce";
    static final String BOB = "a41f9b02-0000-4000-8000-000000000b0b";
    static final String CAROL = "c4401000-0000-4000-8000-0000000ca401";
    static final String SERVICE = "service-account-dlap-backend";
    static final Instant DECIDED = Instant.parse("2026-10-21T09:14:03Z");

    private Approvals() {
    }

    static ApprovalViewDTO view(UUID id, UUID assetId, String status, String actionClass, int required,
            String requestedBy, String fireId, String... approvers) {
        List<ApprovalViewDTO.Decision> decisions = new ArrayList<>();
        for (String a : approvers) {
            decisions.add(new ApprovalViewDTO.Decision(a, "APPROVE", DECIDED));
        }
        boolean executable = "APPROVED".equals(status) || "APPROVED_BREAK_GLASS".equals(status);
        return new ApprovalViewDTO(id, status, executable, fireId == null ? "API" : "POLICY", actionClass, "delete",
                assetId, null, fireId, requestedBy, SERVICE, List.of(requestedBy, SERVICE), required,
                approvers.length, executable ? DECIDED : null, decisions);
    }

    static ApprovalViewDTO withDecisions(ApprovalViewDTO v, String status, List<ApprovalViewDTO.Decision> decisions) {
        return new ApprovalViewDTO(v.id(), status, true, v.origin(), v.actionClass(), v.actionType(), v.assetId(),
                v.assetUri(), v.fireId(), v.requestedBy(), v.submittedBy(), v.excludedSubjects(), v.requiredApprovals(),
                decisions.size(), v.resolvedAt(), decisions);
    }
}
