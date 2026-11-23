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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code approval_requests} (Flyway V7): an action that waits for people.
 *
 * @param id                the request id
 * @param requestKey        idempotency key: {@code fire:<fireId>} for a policy fire,
 *                          {@code api:<submitter>:<key>} for an API request
 * @param origin            {@link #ORIGIN_POLICY} or {@link #ORIGIN_API}
 * @param actionClass       what the action does to data
 * @param actionType        the action type, for example {@code delete}
 * @param actionParameters  the action parameters
 * @param assetId           the catalog asset id
 * @param assetUri          the canonical asset URI; null when unknown
 * @param provider          the cloud provider; null when unknown
 * @param serviceType       the asset kind; null when unknown
 * @param policyDbId        the policy row of a policy fire; null for an API request
 * @param policyId          {@code metadata.id} of that policy
 * @param policyName        {@code metadata.name} of that policy
 * @param fireId            the fire id of a policy fire
 * @param requestedBy       who asks: {@code policy:<policyDbId>}, or a JWT {@code sub}
 * @param requesterKind     {@link #REQUESTER_POLICY}, {@link #REQUESTER_USER} or {@link #REQUESTER_SERVICE}
 * @param submittedBy       who sent the request: {@code da-policyengine} for a fire, else the caller's {@code sub}
 * @param excludedSubjects  subjects that may never decide this request: the requester, the submitter
 *                          and the policy's recorded authors
 * @param reason            why the action is wanted
 * @param requiredApprovals distinct approvals needed
 * @param approvals         approvals so far
 * @param status            the state
 * @param createdAt         when the request was made (business clock)
 * @param expiresAt         when it expires unless decided
 * @param resolvedAt        when it reached a terminal state; null while pending
 * @param actionPayload     the {@code PolicyActionRequest} JSON a policy fire released on approval;
 *                          null for an API request
 * @param dispatchStatus    {@link #DISPATCH_NONE}, {@link #DISPATCH_QUEUED}, {@link #DISPATCH_SENT}
 *                          or {@link #DISPATCH_SHADOWED}
 * @param dispatchedAt      when the released action reached the broker
 * @param withdrawnBy       the service principal that withdrew the request (APPR-03); null unless
 *                          {@link ApprovalStatus#WITHDRAWN}
 * @param onBehalfOf        the person the service account withdrew it for; null unless withdrawn
 * @param withdrawReason    why it was withdrawn; null unless withdrawn
 * @param withdrawnAt       when it was withdrawn (the business clock; equals {@code resolvedAt});
 *                          null unless withdrawn
 */
public record ApprovalRequest(
        UUID id,
        String requestKey,
        String origin,
        ActionClass actionClass,
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
        ApprovalStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant resolvedAt,
        String actionPayload,
        String dispatchStatus,
        Instant dispatchedAt,
        String withdrawnBy,
        String onBehalfOf,
        String withdrawReason,
        Instant withdrawnAt) {

    /** A request made by a policy fire. */
    public static final String ORIGIN_POLICY = "POLICY";
    /** A request made through the REST API. */
    public static final String ORIGIN_API = "API";

    /** The requester is a policy. */
    public static final String REQUESTER_POLICY = "POLICY";
    /** The requester is a person. */
    public static final String REQUESTER_USER = "USER";
    /** The requester is a service account acting for nobody named. */
    public static final String REQUESTER_SERVICE = "SERVICE";

    /** Nothing to release: an API request, or a request that did not end approved. */
    public static final String DISPATCH_NONE = "NONE";
    /** The released action is in the outbox. */
    public static final String DISPATCH_QUEUED = "QUEUED";
    /** The broker acknowledged the released action. */
    public static final String DISPATCH_SENT = "SENT";
    /** Approved while policy actions were disabled (shadow mode): nothing was released. */
    public static final String DISPATCH_SHADOWED = "SHADOWED";

    /** The {@code submittedBy} of a policy fire. */
    public static final String POLICY_ENGINE = "da-policyengine";

    /**
     * A request that was not withdrawn: the withdrawal fields are null.
     *
     * @param id                the request id
     * @param requestKey        the idempotency key
     * @param origin            {@link #ORIGIN_POLICY} or {@link #ORIGIN_API}
     * @param actionClass       what the action does to data
     * @param actionType        the action type
     * @param actionParameters  the action parameters
     * @param assetId           the catalog asset id
     * @param assetUri          the canonical asset URI
     * @param provider          the cloud provider
     * @param serviceType       the asset kind
     * @param policyDbId        the policy row of a policy fire
     * @param policyId          {@code metadata.id} of that policy
     * @param policyName        {@code metadata.name} of that policy
     * @param fireId            the fire id
     * @param requestedBy       who asks
     * @param requesterKind     the kind of requester
     * @param submittedBy       who sent the request
     * @param excludedSubjects  subjects that may never decide it
     * @param reason            why the action is wanted
     * @param requiredApprovals distinct approvals needed
     * @param approvals         approvals so far
     * @param status            the state
     * @param createdAt         when the request was made
     * @param expiresAt         when it expires unless decided
     * @param resolvedAt        when it reached a terminal state
     * @param actionPayload     the released policy action JSON
     * @param dispatchStatus    the dispatch state
     * @param dispatchedAt      when the released action reached the broker
     */
    public ApprovalRequest(UUID id, String requestKey, String origin, ActionClass actionClass, String actionType,
            Map<String, Object> actionParameters, UUID assetId, String assetUri, String provider, String serviceType,
            UUID policyDbId, String policyId, String policyName, String fireId, String requestedBy,
            String requesterKind, String submittedBy, List<String> excludedSubjects, String reason,
            int requiredApprovals, int approvals, ApprovalStatus status, Instant createdAt, Instant expiresAt,
            Instant resolvedAt, String actionPayload, String dispatchStatus, Instant dispatchedAt) {
        this(id, requestKey, origin, actionClass, actionType, actionParameters, assetId, assetUri, provider,
                serviceType, policyDbId, policyId, policyName, fireId, requestedBy, requesterKind, submittedBy,
                excludedSubjects, reason, requiredApprovals, approvals, status, createdAt, expiresAt, resolvedAt,
                actionPayload, dispatchStatus, dispatchedAt, null, null, null, null);
    }

    /**
     * Copies the collections.
     */
    public ApprovalRequest {
        // Not Map.copyOf: JSON parameters may hold null values.
        actionParameters = actionParameters == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(actionParameters));
        excludedSubjects = excludedSubjects == null ? List.of() : List.copyOf(excludedSubjects);
    }
}
