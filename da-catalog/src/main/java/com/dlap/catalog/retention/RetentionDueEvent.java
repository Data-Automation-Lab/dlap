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

package com.dlap.catalog.retention;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.dlap.common.model.RetentionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The message of the retention-due topic (docs/specs/asset-descriptor.md "Retention-due topic"):
 * one per change of {@code retentionStatus}, keyed by the canonical asset URI, a JSON object with
 * camelCase keys and no null fields. The {@code eventId} is the lowercase hex SHA-256 of
 * {@code assetId|previousStatus|status|evaluatedAt}, so a transition that is sent again keeps its
 * id and consumers can drop the repeat.
 *
 * @param eventId the deterministic id
 * @param key     the Kafka key, the asset's URI
 * @param payload the JSON text
 */
public record RetentionDueEvent(String eventId, String key, String payload) {

    /** The {@code schemaVersion} of this payload. */
    public static final String SCHEMA_VERSION = "v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Builds the event of one transition.
     *
     * @param assetId        the asset
     * @param assetUri       the asset's canonical URI
     * @param cloudProvider  the provider
     * @param serviceType    the asset kind
     * @param previousStatus the status before the change
     * @param evaluation     the new result
     * @param legalHold      whether the asset is on legal hold
     * @param evaluatedAt    the evaluation instant
     * @return the event
     */
    public static RetentionDueEvent of(UUID assetId, String assetUri, String cloudProvider, String serviceType,
                                       RetentionStatus previousStatus, RetentionEvaluation evaluation,
                                       boolean legalHold, Instant evaluatedAt) {
        String eventId = eventId(assetId, previousStatus, evaluation.status(), evaluatedAt);
        ObjectNode node = JSON.createObjectNode();
        node.put("schemaVersion", SCHEMA_VERSION);
        node.put("eventId", eventId);
        node.put("assetId", assetId.toString());
        node.put("assetUri", assetUri);
        putIfPresent(node, "cloudProvider", cloudProvider);
        putIfPresent(node, "serviceType", serviceType);
        node.put("previousStatus", previousStatus.name());
        node.put("status", evaluation.status().name());
        putIfPresent(node, "statusReason", evaluation.reason());
        if (evaluation.retentionDays() != null) {
            node.put("retentionDays", evaluation.retentionDays());
        }
        if (evaluation.retentionBasis() != null) {
            node.put("retentionBasis", evaluation.retentionBasis().name());
        }
        putIfPresent(node, "retentionSource", evaluation.retentionSource());
        if (evaluation.retentionPolicyId() != null) {
            node.put("retentionPolicyId", evaluation.retentionPolicyId().toString());
        }
        if (evaluation.expiresAt() != null) {
            node.put("expiresAt", evaluation.expiresAt().toString());
        }
        node.put("legalHold", legalHold);
        node.put("evaluatedAt", evaluatedAt.toString());
        return new RetentionDueEvent(eventId, assetUri, node.toString());
    }

    /**
     * The deterministic event id.
     *
     * @param assetId        the asset
     * @param previousStatus the status before the change
     * @param status         the status after
     * @param evaluatedAt    the evaluation instant
     * @return lowercase hex SHA-256 of {@code assetId|previousStatus|status|evaluatedAt}
     */
    public static String eventId(UUID assetId, RetentionStatus previousStatus, RetentionStatus status,
                                 Instant evaluatedAt) {
        String text = assetId + "|" + previousStatus.name() + "|" + status.name() + "|" + evaluatedAt;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(name, value);
        }
    }
}
