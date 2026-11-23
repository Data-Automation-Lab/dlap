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

import com.dlap.autodelete.client.PolicyEngineApprovalFeignClient;
import com.dlap.autodelete.dto.ApprovalViewDTO;
import com.dlap.autodelete.dto.ApprovalWithdrawRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Withdraws the pending {@code HARD_DELETE} approval of a record that a person restores (Q-621, Q-676).
 *
 * <p>The executor calls the policy engine's service-account-only operation
 * {@code POST /api/v1/policyengine/approvals/{id}/withdraw} (APPR-03) through the approval Feign client, so the
 * call carries the executor's own client-credentials token, never the restoring person's. The body names the
 * restorer ({@code onBehalfOf}, their JWT subject) and a reason of at least 10 characters. The withdrawal is
 * allowed only from {@code PENDING}: 200 answers the request as {@code WITHDRAWN}; 409 answers
 * {@code {"status": current}} when someone decided it first.
 *
 * <p>Best effort by design. The restore does not depend on it: a record in {@code RESTORED} is never executed,
 * whatever happens to its approval, which otherwise expires on its own. A policy engine without the operation
 * (405, or a 404 whose body does not say {@code not-found}: an older release) is logged and recorded in the
 * restore's details as {@code ENDPOINT_NOT_AVAILABLE}, and the restore stands. A 404 whose body reason is
 * {@code not-found} (approvals.md section 8) is the operation answering that the request does not exist:
 * {@code REQUEST_NOT_FOUND} (RET-08, Q-700, decision D38).
 */
@Component
public class ApprovalWithdrawer {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalWithdrawer.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_NOTE = 500;

    /** The reason of the policy engine's 404 for a request it does not know (approvals.md section 8). */
    static final String NOT_FOUND_REASON = "not-found";

    /** What happened to the approval. */
    public enum Result {
        /** The policy engine answered the request as {@code WITHDRAWN}. */
        WITHDRAWN,
        /**
         * The request was no longer {@code PENDING}: someone approved, rejected or released it, or it expired,
         * before the restore (409, the detail is the request's current status).
         */
        APPROVAL_ALREADY_DECIDED,
        /**
         * The policy engine has no withdraw operation: 405, or a 404 whose body reason is not {@code not-found}
         * (an older release).
         */
        ENDPOINT_NOT_AVAILABLE,
        /**
         * The withdraw operation answered 404 with reason {@code not-found}: the policy engine does not know the
         * request (RET-08, Q-700).
         */
        REQUEST_NOT_FOUND,
        /** The policy engine refused the call (400, 401, 403). */
        REFUSED,
        /** The policy engine gave no usable answer. */
        UNREACHABLE,
        /** There is no pending approval to withdraw. */
        NOT_ATTEMPTED
    }

    /**
     * The outcome of one withdrawal.
     *
     * @param result the result
     * @param detail the current status, the machine reason of a refusal, the HTTP status, or null
     */
    public record Outcome(Result result, String detail) {

        /** @return the text recorded in the audit record and the restore answer */
        public String text() {
            return detail == null ? result.name() : result.name() + ":" + detail;
        }
    }

    private final PolicyEngineApprovalFeignClient feign;

    /**
     * Creates the withdrawer.
     *
     * @param feign the approval API, called as the service account
     */
    public ApprovalWithdrawer(PolicyEngineApprovalFeignClient feign) {
        this.feign = feign;
    }

    /**
     * Withdraws a pending request on behalf of the restoring person.
     *
     * @param approvalId      the {@code HARD_DELETE} request
     * @param restorerSubject the restoring person's JWT subject ({@code onBehalfOf})
     * @param recordId        the restored record, named in the reason
     * @param notes           the restorer's notes, or null
     * @return the outcome; never throws
     */
    public Outcome withdraw(UUID approvalId, String restorerSubject, UUID recordId, String notes) {
        if (approvalId == null) {
            return new Outcome(Result.NOT_ATTEMPTED, "no-approval");
        }
        String trimmed = notes == null ? "" : notes.trim();
        if (trimmed.length() > MAX_NOTE) {
            trimmed = trimmed.substring(0, MAX_NOTE);
        }
        String reason = "Soft-delete record " + recordId + " was restored; the hard delete is no longer wanted"
                + (trimmed.isEmpty() ? "" : ". " + trimmed);
        try {
            ApprovalViewDTO view = feign.withdraw(approvalId, new ApprovalWithdrawRequest(reason, restorerSubject));
            String status = view == null ? null : view.status();
            if ("WITHDRAWN".equalsIgnoreCase(status)) {
                return new Outcome(Result.WITHDRAWN, null);
            }
            LOG.warn("Withdrawing approval {} answered 200 with status {}", approvalId, status);
            return new Outcome(Result.APPROVAL_ALREADY_DECIDED, status == null ? "unknown" : status);
        } catch (FeignException e) {
            return interpret(approvalId, e);
        } catch (RuntimeException e) {
            LOG.warn("Could not withdraw approval {}: {}", approvalId, e.getClass().getSimpleName());
            return new Outcome(Result.UNREACHABLE, e.getClass().getSimpleName());
        }
    }

    private static Outcome interpret(UUID approvalId, FeignException e) {
        int status = e.status();
        if (status <= 0) {
            LOG.warn("Could not withdraw approval {}: {}", approvalId, e.getClass().getSimpleName());
            return new Outcome(Result.UNREACHABLE, e.getClass().getSimpleName());
        }
        if (status == 409) {
            String current = field(e.contentUTF8(), "status");
            return new Outcome(Result.APPROVAL_ALREADY_DECIDED, current == null ? "unknown" : current);
        }
        if (status == 404 && NOT_FOUND_REASON.equals(field(e.contentUTF8(), "reason"))) {
            LOG.warn("The policy engine does not know approval {} (HTTP 404 not-found); nothing to withdraw. The "
                    + "restore stands.", approvalId);
            return new Outcome(Result.REQUEST_NOT_FOUND, null);
        }
        if (status == 404 || status == 405) {
            LOG.warn("The policy engine has no approval withdraw operation (HTTP {}); approval {} stays until it "
                    + "is decided or expires. The restore stands.", status, approvalId);
            return new Outcome(Result.ENDPOINT_NOT_AVAILABLE, "http-" + status);
        }
        if (status == 400 || status == 401 || status == 403) {
            String reason = field(e.contentUTF8(), "reason");
            LOG.warn("The policy engine refused the withdrawal of approval {}: HTTP {} {}", approvalId, status, reason);
            return new Outcome(Result.REFUSED, reason != null ? reason : "http-" + status);
        }
        LOG.warn("Could not withdraw approval {}: HTTP {}", approvalId, status);
        return new Outcome(Result.UNREACHABLE, "http-" + status);
    }

    private static String field(String body, String name) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(body);
            String value = n.path(name).asText("");
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null;
        }
    }
}
