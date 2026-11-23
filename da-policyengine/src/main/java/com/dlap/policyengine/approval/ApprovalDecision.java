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

package com.dlap.policyengine.approval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code approval_decisions} (Flyway V7). Immutable in the database.
 *
 * @param id            the decision id
 * @param requestId     the request decided
 * @param approverSub   the decider's JWT {@code sub}
 * @param approverName  the decider's {@code preferred_username}, for display; may be null
 * @param approverRoles the decider's DLAP role authorities at the time
 * @param decision      what was decided
 * @param comment       the comment, or the break-glass justification
 * @param decidedAt     when (business clock)
 */
public record ApprovalDecision(
        UUID id,
        UUID requestId,
        String approverSub,
        String approverName,
        List<String> approverRoles,
        DecisionKind decision,
        String comment,
        Instant decidedAt) {

    /**
     * Copies the role list.
     */
    public ApprovalDecision {
        approverRoles = approverRoles == null ? List.of() : List.copyOf(approverRoles);
    }
}
