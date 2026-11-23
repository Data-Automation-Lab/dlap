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

/**
 * State of an approval request (docs/specs/approvals.md, "States"). A request is created
 * {@link #PENDING} and moves once, to one of the terminal states; the database refuses any other
 * change (Flyway V7 and V8, trigger {@code trg_approval_requests_guard}).
 */
public enum ApprovalStatus {

    /** Waiting for decisions. */
    PENDING,
    /** The quorum of distinct approvers approved it. Executable. */
    APPROVED,
    /** An administrator released it without the quorum, with a justification. Executable; alerted. */
    APPROVED_BREAK_GLASS,
    /** One eligible approver rejected it. Never executable. */
    REJECTED,
    /** Nobody decided before {@code expiresAt}. Never executable. */
    EXPIRED,
    /**
     * A service account withdrew the request on behalf of a person (APPR-03, Q-676). Not a decision
     * and not an approval: nobody approved, nobody rejected. Never executable.
     */
    WITHDRAWN;

    /**
     * Whether an executor may carry out the action of a request in this state.
     *
     * @return true for the two approved states
     */
    public boolean executable() {
        return this == APPROVED || this == APPROVED_BREAK_GLASS;
    }
}
