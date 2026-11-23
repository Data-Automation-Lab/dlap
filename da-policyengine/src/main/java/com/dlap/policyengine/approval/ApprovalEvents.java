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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dlap.common.constants.DlapConstants;

/**
 * Builds the {@code ApprovalEvent} v1 messages of the approval topics (docs/specs/approvals.md,
 * "Events"). Comments are never put on a topic; the break-glass justification is, because the
 * alert has to say why.
 *
 * <p>The {@code eventId} is a name-based UUID of (event type, request id), so a relay that sends a
 * message twice sends the same id and a consumer can drop the repeat.
 */
public final class ApprovalEvents {

    /** {@code schemaVersion} of the event. */
    public static final String SCHEMA_VERSION = "v1";

    /** A request started to wait. */
    public static final String REQUESTED = "REQUESTED";
    /** A request reached a terminal state. */
    public static final String DECIDED = "DECIDED";
    /** A request was released without the quorum. */
    public static final String BREAK_GLASS = "BREAK_GLASS";

    private ApprovalEvents() {
    }

    /**
     * The topic of an event type.
     *
     * @param eventType {@link #REQUESTED}, {@link #DECIDED} or {@link #BREAK_GLASS}
     * @return the topic name from {@link DlapConstants.KafkaTopic}
     */
    public static String topic(String eventType) {
        return switch (eventType) {
            case REQUESTED -> DlapConstants.KafkaTopic.APPROVAL_REQUESTED;
            case DECIDED -> DlapConstants.KafkaTopic.APPROVAL_DECIDED;
            case BREAK_GLASS -> DlapConstants.KafkaTopic.APPROVAL_BREAK_GLASS;
            default -> throw new IllegalArgumentException("unknown approval event type " + eventType);
        };
    }

    /**
     * The event of one request.
     *
     * @param eventType     the event type
     * @param request       the request as it is after the change
     * @param decisions     its decisions, in order; ignored for {@link #REQUESTED}
     * @param occurredAt    when the change happened
     * @param justification the break-glass justification; only for {@link #BREAK_GLASS}
     * @return the event, as an ordered map ready for JSON
     */
    public static Map<String, Object> event(String eventType, ApprovalRequest request, List<ApprovalDecision> decisions,
            Instant occurredAt, String justification) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("schemaVersion", SCHEMA_VERSION);
        e.put("eventId", UUID.nameUUIDFromBytes((eventType + "|" + request.id()).getBytes(StandardCharsets.UTF_8))
                .toString());
        e.put("eventType", eventType);
        e.put("occurredAt", occurredAt.toString());
        e.put("approvalId", request.id().toString());
        e.put("status", request.status().name());
        e.put("executable", request.status().executable());
        e.put("origin", request.origin());
        e.put("actionClass", request.actionClass().name());
        e.put("actionType", request.actionType());
        e.put("actionParameters", request.actionParameters());
        e.put("assetId", request.assetId().toString());
        e.put("assetUri", request.assetUri());
        e.put("provider", request.provider());
        e.put("serviceType", request.serviceType());
        e.put("policyDbId", request.policyDbId() == null ? null : request.policyDbId().toString());
        e.put("policyId", request.policyId());
        e.put("fireId", request.fireId());
        e.put("requestedBy", request.requestedBy());
        e.put("requesterKind", request.requesterKind());
        e.put("submittedBy", request.submittedBy());
        e.put("reason", request.reason());
        e.put("requiredApprovals", request.requiredApprovals());
        e.put("approvals", request.approvals());
        e.put("createdAt", request.createdAt().toString());
        e.put("expiresAt", request.expiresAt().toString());
        e.put("resolvedAt", request.resolvedAt() == null ? null : request.resolvedAt().toString());
        if (!REQUESTED.equals(eventType)) {
            List<Map<String, Object>> ds = new ArrayList<>();
            for (ApprovalDecision d : decisions) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("approverSub", d.approverSub());
                m.put("decision", d.decision().name());
                m.put("decidedAt", d.decidedAt().toString());
                ds.add(m);
            }
            e.put("decisions", ds);
        }
        if (request.status() == ApprovalStatus.WITHDRAWN) {
            // Who and for whom, never the free-text reason (read it through the API, like a comment).
            e.put("withdrawnBy", request.withdrawnBy());
            e.put("onBehalfOf", request.onBehalfOf());
            e.put("withdrawnAt", request.withdrawnAt() == null ? null : request.withdrawnAt().toString());
        }
        if (BREAK_GLASS.equals(eventType)) {
            e.put("justification", justification);
        }
        return e;
    }

    /**
     * The subjects whose decision released a request, in decision order: every approver of a
     * quorum, or the administrator who broke glass.
     *
     * @param decisions the decisions of the request
     * @return the subjects
     */
    public static List<String> approvers(List<ApprovalDecision> decisions) {
        List<String> out = new ArrayList<>();
        for (ApprovalDecision d : decisions) {
            if (d.decision() == DecisionKind.APPROVE || d.decision() == DecisionKind.BREAK_GLASS) {
                out.add(d.approverSub());
            }
        }
        return out;
    }
}
