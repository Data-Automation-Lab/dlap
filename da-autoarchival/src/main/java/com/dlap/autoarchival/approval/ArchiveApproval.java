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

package com.dlap.autoarchival.approval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval an archive action carries or was asked for (docs/specs/approvals.md section 7).
 *
 * @param approvalId        the approval request id
 * @param status            {@code APPROVED} or {@code APPROVED_BREAK_GLASS}
 * @param actionClass       the action class the approval was given for; an archive needs {@code ARCHIVE}
 * @param requiredApprovals the quorum
 * @param approvers         the JWT {@code sub} of each approver, in decision order
 * @param breakGlass        whether an administrator released it without the quorum
 * @param assetUri          the asset location the approval was given for, as the policy engine holds it; may be
 *                          {@code null} when the approval named none (an event-claimed approval never has it)
 * @param provider          the provider the approval was given for; may be {@code null}
 * @param actionParameters  the parameters the approval was given for (tier, prefix, afterDays); never {@code null}
 */
public record ArchiveApproval(String approvalId, String status, String actionClass, int requiredApprovals,
        List<String> approvers, boolean breakGlass, String assetUri, String provider,
        Map<String, Object> actionParameters) {

    /** Status of a request whose quorum approved it. */
    public static final String APPROVED = "APPROVED";
    /** Status of a request an administrator released without the quorum. */
    public static final String APPROVED_BREAK_GLASS = "APPROVED_BREAK_GLASS";
    /** The action class an archive needs. */
    public static final String ACTION_CLASS_ARCHIVE = "ARCHIVE";

    /**
     * Creates the record and copies the approver list.
     *
     * @param approvalId        the approval request id
     * @param status            the status
     * @param actionClass       the action class
     * @param requiredApprovals the quorum
     * @param approvers         the approvers' subjects
     * @param breakGlass        whether it was a break-glass release
     * @param assetUri          the approved asset location, or {@code null}
     * @param provider          the approved provider, or {@code null}
     * @param actionParameters  the approved parameters, or {@code null} for none
     */
    public ArchiveApproval {
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
        actionParameters = actionParameters == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(actionParameters));
    }

    /**
     * Creates an approval that carries no scope (what an event claims before the policy engine is asked).
     *
     * @param approvalId        the approval request id
     * @param status            the status
     * @param actionClass       the action class
     * @param requiredApprovals the quorum
     * @param approvers         the approvers' subjects
     * @param breakGlass        whether it was a break-glass release
     */
    public ArchiveApproval(String approvalId, String status, String actionClass, int requiredApprovals,
            List<String> approvers, boolean breakGlass) {
        this(approvalId, status, actionClass, requiredApprovals, approvers, breakGlass, null, null, null);
    }
}
