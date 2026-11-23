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

package com.dlap.policyengine.event;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The {@code PolicyActionRequest} v1 wire shape on {@code dlap.policies.actions}
 * ({@code DlapConstants.KafkaTopic.POLICY_ACTION_EVENT}), as specified in
 * {@code docs/specs/policy-dsl.md}, section "Wire format". Every policy-engine producer path (event
 * trigger, schedule trigger, on-demand REST evaluation) publishes this one shape (POL-01).
 *
 * <p>The asset identity is top-level so a consumer never has to dig it out of the snapshot:
 * {@code assetId} (catalog id), {@code assetUri} (canonical URI, ADR-0047), {@code provider} and
 * {@code serviceType}. A value DLAP does not know is null, never a guess. The core fields are
 * always written, null or not; the path-specific ones ({@code scheduledFor},
 * {@code sourceEventId}, {@code evaluationId}) only when set.
 *
 * @param schemaVersion    constant {@link #SCHEMA_VERSION}
 * @param policyId         {@code metadata.id} of the policy document; on the REST path the policy
 *                         row UUID, as before (consumers that need the row read {@code policyDbId})
 * @param policyDbId       the policy row UUID
 * @param policyName       {@code metadata.name}
 * @param fireId           id of this fire; deterministic per (policy, trigger instance, asset), so a
 *                         redelivered or re-run fire carries the same id
 * @param firedAt          ISO-8601 instant of the decision (the evaluation's {@code now})
 * @param triggerType      {@code event}, {@code schedule} or {@code manual}
 * @param triggerLifecycle lifecycle shorthand of the inbound topic (event fires); null otherwise
 * @param scheduledFor     the cron slot a schedule fire belongs to; null otherwise
 * @param sourceEventId    the inbound event's id (event fires) when it carries one
 * @param evaluationId     the evaluation record id (REST fires)
 * @param assetId          catalog asset id; never null on a published action
 * @param assetUri         canonical asset URI; null when unknown
 * @param provider         cloud provider, for example {@code GCP}; null when unknown
 * @param serviceType      asset kind, for example {@code GCS_BUCKET}; null when unknown
 * @param actionType       {@code spec.action.type}, or the legacy actions-map key on the REST path
 * @param actionParameters action parameters; {@code {}} when absent
 * @param requireApproval  {@code spec.action.requireApproval}
 * @param dryRun           {@code spec.action.dryRun}
 * @param matchSnapshot    the snapshot the match ran against
 * @param approval         set only on an action released by an approval (APPR-01,
 *                         docs/specs/approvals.md): who approved it and under which rule; omitted
 *                         from the JSON otherwise
 */
@JsonPropertyOrder({"schemaVersion", "policyId", "policyDbId", "policyName", "fireId", "firedAt",
        "triggerType", "triggerLifecycle", "scheduledFor", "sourceEventId", "evaluationId",
        "assetId", "assetUri", "provider", "serviceType",
        "actionType", "actionParameters", "requireApproval", "dryRun", "matchSnapshot", "approval"})
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PolicyActionEvent(
        String schemaVersion,
        String policyId,
        String policyDbId,
        String policyName,
        String fireId,
        String firedAt,
        String triggerType,
        String triggerLifecycle,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scheduledFor,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceEventId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String evaluationId,
        String assetId,
        String assetUri,
        String provider,
        String serviceType,
        String actionType,
        Map<String, Object> actionParameters,
        boolean requireApproval,
        boolean dryRun,
        Map<String, Object> matchSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL) Approval approval) {

    /** {@code schemaVersion} of this shape. */
    public static final String SCHEMA_VERSION = "v1";

    /** {@code triggerType} of an event-trigger fire. */
    public static final String TRIGGER_EVENT = "event";

    /** {@code triggerType} of a schedule-trigger fire. */
    public static final String TRIGGER_SCHEDULE = "schedule";

    /** {@code triggerType} of an on-demand REST evaluation. */
    public static final String TRIGGER_MANUAL = "manual";

    /**
     * Normalises the maps: absent parameters and snapshot become empty maps.
     */
    public PolicyActionEvent {
        actionParameters = actionParameters == null ? Map.of() : actionParameters;
        matchSnapshot = matchSnapshot == null ? Map.of() : matchSnapshot;
    }

    /**
     * An action as a policy fires it, before any approval: {@code approval} is null.
     *
     * @param schemaVersion    constant {@link #SCHEMA_VERSION}
     * @param policyId         {@code metadata.id} of the policy document
     * @param policyDbId       the policy row UUID
     * @param policyName       {@code metadata.name}
     * @param fireId           id of this fire
     * @param firedAt          ISO-8601 instant of the decision
     * @param triggerType      {@code event}, {@code schedule} or {@code manual}
     * @param triggerLifecycle lifecycle shorthand of the inbound topic, or null
     * @param scheduledFor     the cron slot of a schedule fire, or null
     * @param sourceEventId    the inbound event's id, or null
     * @param evaluationId     the evaluation record id, or null
     * @param assetId          catalog asset id
     * @param assetUri         canonical asset URI, or null
     * @param provider         cloud provider, or null
     * @param serviceType      asset kind, or null
     * @param actionType       {@code spec.action.type}
     * @param actionParameters action parameters
     * @param requireApproval  {@code spec.action.requireApproval}
     * @param dryRun           {@code spec.action.dryRun}
     * @param matchSnapshot    the snapshot the match ran against
     */
    public PolicyActionEvent(String schemaVersion, String policyId, String policyDbId, String policyName,
            String fireId, String firedAt, String triggerType, String triggerLifecycle, String scheduledFor,
            String sourceEventId, String evaluationId, String assetId, String assetUri, String provider,
            String serviceType, String actionType, Map<String, Object> actionParameters, boolean requireApproval,
            boolean dryRun, Map<String, Object> matchSnapshot) {
        this(schemaVersion, policyId, policyDbId, policyName, fireId, firedAt, triggerType, triggerLifecycle,
                scheduledFor, sourceEventId, evaluationId, assetId, assetUri, provider, serviceType, actionType,
                actionParameters, requireApproval, dryRun, matchSnapshot, null);
    }

    /**
     * This action as released by an approval: the same fire with {@code requireApproval: false},
     * because the approval it waited for exists, and the approval block that says so.
     *
     * @param approval the approval that released the action
     * @return the released action
     */
    public PolicyActionEvent approved(Approval approval) {
        if (approval == null) {
            throw new IllegalArgumentException("an approved action needs its approval");
        }
        return new PolicyActionEvent(schemaVersion, policyId, policyDbId, policyName, fireId, firedAt, triggerType,
                triggerLifecycle, scheduledFor, sourceEventId, evaluationId, assetId, assetUri, provider, serviceType,
                actionType, actionParameters, false, dryRun, matchSnapshot, approval);
    }

    /**
     * The approval that released an action (APPR-01). A consumer that executes destructive actions
     * checks {@code status}, and for a hard delete {@code approvers}, before it acts; it may also
     * re-read the request at {@code GET /api/v1/policyengine/approvals/{approvalId}}.
     *
     * @param approvalId        the approval request id
     * @param status            {@code APPROVED} or {@code APPROVED_BREAK_GLASS}
     * @param actionClass       {@code ARCHIVE}, {@code SOFT_DELETE}, {@code HARD_DELETE} or {@code OTHER}
     * @param requiredApprovals distinct approvals the request needed
     * @param approvers         JWT {@code sub} of each approver, in decision order
     * @param breakGlass        true when an administrator released it without the quorum
     * @param decidedAt         ISO-8601 instant of the deciding decision
     */
    @JsonPropertyOrder({"approvalId", "status", "actionClass", "requiredApprovals", "approvers", "breakGlass",
            "decidedAt"})
    public record Approval(
            String approvalId,
            String status,
            String actionClass,
            int requiredApprovals,
            List<String> approvers,
            boolean breakGlass,
            String decidedAt) {

        /**
         * Copies the approver list.
         */
        public Approval {
            approvers = approvers == null ? List.of() : List.copyOf(approvers);
        }
    }
}
