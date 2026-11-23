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

package com.dlap.autolabel.kafka;

import com.dlap.autolabel.config.KafkaReliabilityConfiguration;
import com.dlap.autolabel.service.ILabelingRunService;
import com.dlap.autolabel.service.PolicyFireId;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link DlapConstants.KafkaTopic#POLICY_ACTION_EVENT}
 * ({@code dlap.policies.actions}) and applies policy-driven
 * {@code addLabel} actions to the target catalog asset.
 *
 * <p>The policy engine emits a JSON map (see
 * {@code da-policyengine LifecyclePolicyConsumer}) with fields
 * {@code assetId}, {@code actionType} (author-defined, e.g. {@code addLabel}),
 * {@code actionParameters} (e.g. {@code {"labelName":"PII"}}), plus
 * {@code policyId}/{@code policyName}. This service is the ONLY autolabel
 * consumer of that topic; da-autoarchival / da-autodelete / da-reporting
 * consume it for their own action types and ignore {@code addLabel}.
 *
 * <p>On an addLabel action we call the catalog client to POST the label onto
 * the asset AND record a {@code LabelingJob} (source POLICY_ENGINE) so the
 * label actually lands and the dashboard reflects it. Non-addLabel actions are
 * acknowledged and skipped. The autolabel MANUAL_ACK_FACTORY delivers the value
 * as a raw JSON String, so we parse it here rather than binding a typed payload.
 *
 * <p>The {@code fireId} is trimmed and a blank one counts as absent (no dedupe key); one longer than
 * {@link PolicyFireId#MAX_LENGTH} characters is refused, logged and acknowledged without a retry (Q-890).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolicyActionLabelConsumer {

    private final ILabelingRunService labelingRunService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
            groupId = "da-autolabel-policy-actions",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY
    )
    public void onPolicyAction(String payload, Acknowledgment ack) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Skipping unparseable policy-action payload ({}). Acknowledging to drain.", e.getMessage());
            ack.acknowledge();
            return;
        }

        String actionType = text(node, "actionType");
        if (!isAddLabelAction(actionType)) {
            log.debug("Skipping policy action '{}' (not an addLabel).", actionType);
            ack.acknowledge();
            return;
        }

        UUID assetId = parseAssetId(node);
        String labelName = extractLabelName(node);
        if (assetId == null || labelName == null || labelName.isBlank()) {
            log.warn("addLabel action missing usable assetId ({}) or labelName ({}). Acknowledging to drain.",
                    text(node, "assetId"), labelName);
            ack.acknowledge();
            return;
        }

        String policyId = text(node, "policyId");
        String policyName = text(node, "policyName");
        // The fireId is the idempotency key (ADR-0135): trimmed, a blank one is no key, and one that cannot be
        // stored is refused here. Retrying it could never succeed, so it is acknowledged, not sent to the DLT.
        String fireId = PolicyFireId.normalize(text(node, "fireId"));
        if (PolicyFireId.exceedsMaxLength(fireId)) {
            log.warn("Refusing addLabel '{}' for asset {}: fireId is {} characters, the limit is {}. "
                    + "Acknowledging without retry.", labelName, assetId, fireId.length(), PolicyFireId.MAX_LENGTH);
            ack.acknowledge();
            return;
        }
        try {
            String jobId = labelingRunService.applyPolicyLabel(assetId, labelName, policyId, policyName, fireId);
            log.info("Applied policy addLabel '{}' to asset {} (job {})", labelName, assetId, jobId);
            ack.acknowledge();
        } catch (Exception e) {
            // Do not ack - retry / DLQ via DefaultErrorHandler.
            log.error("Failed to apply policy addLabel '{}' to asset {}: {}; will retry / DLQ",
                    labelName, assetId, e.getMessage());
            throw new RuntimeException("Policy addLabel failed for asset " + assetId, e);
        }
    }

    private static boolean isAddLabelAction(String actionType) {
        if (actionType == null) {
            return false;
        }
        // Match addLabel / ADD_LABEL / add-label variants case-insensitively.
        String normalized = actionType.trim().toUpperCase(Locale.ROOT).replace("-", "_");
        return normalized.equals("ADDLABEL") || normalized.equals("ADD_LABEL");
    }

    private UUID parseAssetId(JsonNode node) {
        String raw = text(node, "assetId");
        if (raw == null) {
            raw = text(node, "targetAssetId");
        }
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Policy action assetId '{}' is not a catalog UUID.", raw);
            return null;
        }
    }

    /**
     * Label name lives in {@code actionParameters.labelName}. Tolerate a couple
     * of shapes the policy engine may emit: a nested object, or a bare string
     * value on {@code actionParameters} / a top-level {@code labelName}.
     */
    private String extractLabelName(JsonNode node) {
        JsonNode params = node.get("actionParameters");
        if (params != null) {
            if (params.hasNonNull("labelName")) {
                return params.get("labelName").asText();
            }
            if (params.hasNonNull("label")) {
                return params.get("label").asText();
            }
            if (params.isTextual()) {
                return params.asText();
            }
        }
        return text(node, "labelName");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }
}
