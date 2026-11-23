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
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of the approval API (docs/specs/approvals.md, "REST API").
 */
public final class ApprovalApi {

    private ApprovalApi() {
    }

    /**
     * Body of {@code POST /api/v1/policyengine/approvals}.
     *
     * @param actionClass       {@code ARCHIVE}, {@code SOFT_DELETE}, {@code HARD_DELETE} or {@code OTHER}
     * @param actionType        the action the executor will run, for example {@code delete}
     * @param assetId           the catalog asset id
     * @param assetUri          the canonical asset URI
     * @param provider          the cloud provider
     * @param serviceType       the asset kind
     * @param actionParameters  what the executor needs to act
     * @param reason            why the action is wanted
     * @param requiredApprovals more approvals than the class needs; never fewer
     * @param idempotencyKey    a retry with the same key returns the first request
     * @param onBehalfOf        service accounts only: the {@code sub} of the person they act for
     */
    public record ApprovalCreateBody(
            @NotBlank @Size(max = 16) String actionClass,
            @NotBlank @Size(max = 64) String actionType,
            @NotNull UUID assetId,
            @Size(max = 2048) String assetUri,
            @Size(max = 32) String provider,
            @Size(max = 64) String serviceType,
            Map<String, Object> actionParameters,
            @NotBlank @Size(max = 10000) String reason,
            @Min(1) @Max(10) Integer requiredApprovals,
            @Size(max = 128) String idempotencyKey,
            @Size(max = 255) String onBehalfOf) {
    }

    /**
     * Body of {@code POST /api/v1/policyengine/approvals/{id}/decisions}.
     *
     * @param decision {@code APPROVE} or {@code REJECT}
     * @param comment  why; required
     */
    public record ApprovalDecisionBody(
            @NotBlank @Size(max = 16) String decision,
            @NotBlank @Size(max = 10000) String comment) {
    }

    /**
     * Body of {@code POST /api/v1/policyengine/approvals/{id}/break-glass}.
     *
     * @param justification why the quorum cannot wait; at least 20 characters
     */
    public record ApprovalBreakGlassBody(@NotBlank @Size(max = 10000) String justification) {
    }

    /**
     * Body of {@code POST /api/v1/policyengine/approvals/{id}/withdraw}: service accounts only.
     *
     * @param reason     why the request is withdrawn; at least 10 characters
     * @param onBehalfOf the {@code sub} of the person the service account withdraws it for
     */
    public record ApprovalWithdrawBody(
            @NotBlank @Size(min = 10, max = 10000) String reason,
            @NotBlank @Size(max = 255) String onBehalfOf) {
    }

    /**
     * One decision as the API shows it.
     *
     * @param approverSub  the decider's JWT {@code sub}
     * @param approverName the decider's {@code preferred_username}
     * @param decision     {@code APPROVE}, {@code REJECT} or {@code BREAK_GLASS}
     * @param comment      the comment or justification
     * @param decidedAt    when
     */
    public record ApprovalDecisionView(String approverSub, String approverName, String decision, String comment,
            Instant decidedAt) {

        static ApprovalDecisionView of(ApprovalDecision d) {
            return new ApprovalDecisionView(d.approverSub(), d.approverName(), d.decision().name(), d.comment(), d.decidedAt());
        }
    }

    /**
     * One request as the API shows it.
     *
     * @param id                the request id
     * @param status            the state
     * @param executable        whether an executor may act on it now
     * @param origin            {@code POLICY} or {@code API}
     * @param actionClass       the action class
     * @param actionType        the action type
     * @param actionParameters  the action parameters
     * @param assetId           the catalog asset id
     * @param assetUri          the canonical asset URI
     * @param provider          the cloud provider
     * @param serviceType       the asset kind
     * @param policyDbId        the policy row of a policy fire
     * @param policyId          the policy's {@code metadata.id}
     * @param policyName        the policy's {@code metadata.name}
     * @param fireId            the fire id of a policy fire
     * @param requestedBy       who asks
     * @param requesterKind     {@code POLICY}, {@code USER} or {@code SERVICE}
     * @param submittedBy       who sent it
     * @param excludedSubjects  subjects that may never decide it
     * @param reason            why the action is wanted
     * @param requiredApprovals distinct approvals needed
     * @param approvals         approvals so far
     * @param createdAt         when it was made
     * @param expiresAt         when it expires unless decided
     * @param resolvedAt        when it reached its terminal state
     * @param dispatchStatus    {@code NONE}, {@code QUEUED}, {@code SENT} or {@code SHADOWED}
     * @param dispatchedAt      when the released action reached the broker
     * @param withdrawnBy       the service principal that withdrew it; null unless {@code WITHDRAWN}
     * @param onBehalfOf        the person it was withdrawn for; null unless {@code WITHDRAWN}
     * @param withdrawReason    why it was withdrawn; null unless {@code WITHDRAWN}
     * @param withdrawnAt       when it was withdrawn; null unless {@code WITHDRAWN}
     * @param decisions         the decisions, in order
     */
    public record ApprovalView(
            UUID id,
            String status,
            boolean executable,
            String origin,
            String actionClass,
            String actionType,
            Map<String, Object> actionParameters,
            UUID assetId,
            String assetUri,
            String provider,
            String serviceType,
            UUID policyDbId,
            String policyId,
            String policyName,
            String fireId,
            String requestedBy,
            String requesterKind,
            String submittedBy,
            List<String> excludedSubjects,
            String reason,
            int requiredApprovals,
            int approvals,
            Instant createdAt,
            Instant expiresAt,
            Instant resolvedAt,
            String dispatchStatus,
            Instant dispatchedAt,
            String withdrawnBy,
            String onBehalfOf,
            String withdrawReason,
            Instant withdrawnAt,
            List<ApprovalDecisionView> decisions) {

        static ApprovalView of(ApprovalRequest r, List<ApprovalDecision> decisions) {
            return new ApprovalView(r.id(), r.status().name(), r.status().executable(), r.origin(),
                    r.actionClass().name(), r.actionType(), r.actionParameters(), r.assetId(), r.assetUri(),
                    r.provider(), r.serviceType(), r.policyDbId(), r.policyId(), r.policyName(), r.fireId(),
                    r.requestedBy(), r.requesterKind(), r.submittedBy(), r.excludedSubjects(), r.reason(),
                    r.requiredApprovals(), r.approvals(), r.createdAt(), r.expiresAt(), r.resolvedAt(),
                    r.dispatchStatus(), r.dispatchedAt(), r.withdrawnBy(), r.onBehalfOf(), r.withdrawReason(),
                    r.withdrawnAt(),
                    decisions == null ? null : decisions.stream().map(ApprovalDecisionView::of).toList());
        }

        static ApprovalView of(ApprovalService.Detail detail) {
            return of(detail.request(), detail.decisions());
        }
    }

    /**
     * A page of requests. List rows carry no decisions ({@code decisions} is null); read one request
     * for them.
     *
     * @param content       the rows
     * @param page          the page, from 0
     * @param size          the page size
     * @param totalElements all matching rows
     * @param totalPages    pages at this size
     */
    public record ApprovalPageView(List<ApprovalView> content, int page, int size, long totalElements, long totalPages) {

        static ApprovalPageView of(ApprovalService.Page p) {
            List<ApprovalView> rows = p.rows().stream().map(r -> ApprovalView.of(r, null)).toList();
            long pages = p.size() == 0 ? 0 : (p.total() + p.size() - 1) / p.size();
            return new ApprovalPageView(rows, p.page(), p.size(), p.total(), pages);
        }
    }
}
