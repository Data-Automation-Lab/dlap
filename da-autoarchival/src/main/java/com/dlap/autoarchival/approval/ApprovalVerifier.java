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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.dlap.autoarchival.approval.ApprovalRefusedException.Reason;

/**
 * The executor side of docs/specs/approvals.md section 9, for the archive class.
 *
 * <ol>
 *   <li>{@link #fromEvent}: rule 1. An archive that reaches the executor without an
 *       {@code approval} object of status {@code APPROVED} or {@code APPROVED_BREAK_GLASS}, or with
 *       one given for another action class, is refused. {@code requireApproval} is not consulted:
 *       an archive always needs an approval (rule 1 of section 2 of the spec).</li>
 *   <li>{@link #verify}: rule 3. The approval is re-read from the policy engine as
 *       {@code ROLE_SERVICE_ACCOUNT}; the executor acts only when the policy engine says
 *       {@code executable} for the same asset. The re-read is not optional here: the event alone
 *       can be forged by anyone who can write to the topic.</li>
 * </ol>
 *
 * <p>An archive has no quorum rule beyond "executable" (the two-approver floor is for hard
 * deletes, rule 2).
 */
@Component
public class ApprovalVerifier {

    private final PolicyEngineApprovalClient client;

    /**
     * Creates the verifier.
     *
     * @param client the policy engine client
     */
    public ApprovalVerifier(PolicyEngineApprovalClient client) {
        this.client = client;
    }

    /**
     * Reads the approval block of a policy action and applies section 9 rule 1.
     *
     * @param event the {@code PolicyActionRequest} as received
     * @return the approval the action carries
     * @throws ApprovalRefusedException {@code NO_APPROVAL}, {@code NOT_APPROVED} or {@code WRONG_CLASS}
     */
    public ArchiveApproval fromEvent(Map<String, Object> event) {
        Object block = event == null ? null : event.get("approval");
        if (!(block instanceof Map<?, ?> m)) {
            throw new ApprovalRefusedException(Reason.NO_APPROVAL, "the archive action carries no approval");
        }
        String id = text(m.get("approvalId"));
        if (id.isBlank()) {
            throw new ApprovalRefusedException(Reason.NO_APPROVAL, "the approval has no approvalId");
        }
        String status = text(m.get("status"));
        if (!ArchiveApproval.APPROVED.equals(status) && !ArchiveApproval.APPROVED_BREAK_GLASS.equals(status)) {
            throw new ApprovalRefusedException(Reason.NOT_APPROVED, "approval " + id + " has status '" + status + "'");
        }
        String actionClass = text(m.get("actionClass"));
        if (!ArchiveApproval.ACTION_CLASS_ARCHIVE.equals(actionClass)) {
            throw new ApprovalRefusedException(Reason.WRONG_CLASS,
                    "approval " + id + " was given for '" + actionClass + "', not ARCHIVE");
        }
        List<String> approvers = new ArrayList<>();
        if (m.get("approvers") instanceof List<?> list) {
            for (Object o : list) {
                approvers.add(String.valueOf(o));
            }
        }
        int required = m.get("requiredApprovals") instanceof Number n ? n.intValue() : 1;
        return new ArchiveApproval(id, status, actionClass, required, approvers, Boolean.TRUE.equals(m.get("breakGlass")));
    }

    /**
     * Checks the approval with the policy engine (section 9, rule 3) just before the action.
     *
     * @param claimed the approval the action claims
     * @param assetId the catalog asset id the action runs on
     * @return the approval as the policy engine reports it
     * @throws ApprovalRefusedException when the policy engine does not call it executable for this
     *                                  asset, or cannot be asked
     */
    public ArchiveApproval verify(ArchiveApproval claimed, String assetId) {
        return client.fetchExecutable(claimed.approvalId(), assetId);
    }

    /**
     * Checks an approval known only by its id (an archive requested through the REST API).
     *
     * @param approvalId the approval id
     * @param assetId    the catalog asset id
     * @return the approval as the policy engine reports it
     * @throws ApprovalRefusedException when it is not executable for this asset, or cannot be asked
     */
    public ArchiveApproval verifyById(String approvalId, String assetId) {
        return client.fetchExecutable(approvalId, assetId);
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
