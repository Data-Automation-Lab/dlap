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

package com.dlap.policyengine.dsl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.dlap.policyengine.event.PolicyActionEvent;

/**
 * Builds the {@link PolicyActionEvent} ({@code PolicyActionRequest} v1) for each trigger path
 * (POL-01). The asset identity comes from the evaluated snapshot ({@link AssetSnapshots} keys):
 * {@code assetId}, {@code assetUri}, {@code provider} (the snapshot's {@code cloudProvider}) and
 * {@code serviceType}.
 *
 * <p>Fire ids are name-based UUIDs of the trigger instance, so a redelivered event, a re-run
 * sweep or a retried evaluation publishes the same {@code fireId} and a consumer that stores it
 * (da-autodelete's {@code policyFireId}) can tell a repeat from a new decision.
 */
public final class PolicyActionRequests {

    private PolicyActionRequests() {
    }

    /**
     * An event-trigger fire.
     *
     * @param dsl           the policy document
     * @param policyDbId    the policy row id
     * @param snapshot      the evaluated snapshot; must hold {@code assetId}
     * @param lifecycle     lifecycle shorthand of the inbound topic; may be {@code null}
     * @param sourceEventId the inbound event's id; may be {@code null}
     * @param firedAt       the decision instant
     * @return the action
     */
    public static PolicyActionEvent forEvent(PolicyDsl dsl, UUID policyDbId, Map<String, Object> snapshot,
            String lifecycle, String sourceEventId, Instant firedAt) {
        String assetId = assetId(snapshot);
        String fireId = sourceEventId != null
                ? nameUuid("event", str(policyDbId), sourceEventId, assetId)
                : UUID.randomUUID().toString();
        PolicyDsl.Action action = dsl.getSpec().getAction();
        return build(dsl.getMetadata().getId(), str(policyDbId), dsl.getMetadata().getName(), fireId, firedAt,
                PolicyActionEvent.TRIGGER_EVENT, lifecycle, null, sourceEventId, null,
                snapshot, action.getType(), action.getParameters(), action.isRequireApproval(), action.isDryRun());
    }

    /**
     * A schedule-trigger fire.
     *
     * @param dsl        the policy document
     * @param policyDbId the policy row id
     * @param snapshot   the evaluated snapshot; must hold {@code assetId}
     * @param slot       the cron slot the sweep runs for
     * @param asOf       the sweep's as-of instant (the decision instant)
     * @return the action
     */
    public static PolicyActionEvent forSchedule(PolicyDsl dsl, UUID policyDbId, Map<String, Object> snapshot,
            Instant slot, Instant asOf) {
        String assetId = assetId(snapshot);
        PolicyDsl.Action action = dsl.getSpec().getAction();
        return build(dsl.getMetadata().getId(), str(policyDbId), dsl.getMetadata().getName(),
                nameUuid("schedule", str(policyDbId), slot.toString(), assetId), asOf,
                PolicyActionEvent.TRIGGER_SCHEDULE, null, slot.toString(), null, null,
                snapshot, action.getType(), action.getParameters(), action.isRequireApproval(), action.isDryRun());
    }

    /**
     * An on-demand REST evaluation fire. The policy id stays the row UUID on this path, as
     * before; the safety flags come from the document's {@code spec.action}.
     *
     * @param policyDbId       the policy row id
     * @param policyName       the policy name
     * @param dslAction        the document's {@code spec.action}; may be {@code null}
     * @param actionType       the action type to publish
     * @param actionParameters its parameters; may be {@code null}
     * @param snapshot         the evaluated snapshot; must hold {@code assetId}
     * @param evaluationId     the evaluation record id; may be {@code null}
     * @param firedAt          the decision instant
     * @return the action
     */
    public static PolicyActionEvent forManual(UUID policyDbId, String policyName, PolicyDsl.Action dslAction,
            String actionType, Map<String, Object> actionParameters, Map<String, Object> snapshot,
            UUID evaluationId, Instant firedAt) {
        String fireId = evaluationId != null
                ? nameUuid("manual", evaluationId.toString(), actionType)
                : UUID.randomUUID().toString();
        return build(str(policyDbId), str(policyDbId), policyName, fireId, firedAt,
                PolicyActionEvent.TRIGGER_MANUAL, null, null, null, str(evaluationId),
                snapshot, actionType, actionParameters,
                dslAction != null && dslAction.isRequireApproval(), dslAction != null && dslAction.isDryRun());
    }

    /**
     * The catalog asset id a snapshot names.
     *
     * @param snapshot the snapshot; may be {@code null}
     * @return the id, or {@code null} when the snapshot names none
     */
    public static String assetId(Map<String, Object> snapshot) {
        return AssetSnapshots.firstText(snapshot, AssetSnapshots.ASSET_ID, "id");
    }

    private static PolicyActionEvent build(String policyId, String policyDbId, String policyName, String fireId,
            Instant firedAt, String triggerType, String lifecycle, String scheduledFor, String sourceEventId,
            String evaluationId, Map<String, Object> snapshot, String actionType, Map<String, Object> parameters,
            boolean requireApproval, boolean dryRun) {
        Map<String, Object> snap = snapshot != null ? new LinkedHashMap<>(snapshot) : new LinkedHashMap<>();
        return new PolicyActionEvent(
                PolicyActionEvent.SCHEMA_VERSION,
                policyId,
                policyDbId,
                policyName,
                fireId,
                firedAt.toString(),
                triggerType,
                lifecycle,
                scheduledFor,
                sourceEventId,
                evaluationId,
                assetId(snap),
                AssetSnapshots.firstText(snap, AssetSnapshots.ASSET_URI),
                AssetSnapshots.firstText(snap, AssetSnapshots.CLOUD_PROVIDER),
                AssetSnapshots.firstText(snap, AssetSnapshots.SERVICE_TYPE),
                actionType,
                parameters != null ? new LinkedHashMap<>(parameters) : Map.of(),
                requireApproval,
                dryRun,
                snap);
    }

    private static String nameUuid(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("|", parts).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
