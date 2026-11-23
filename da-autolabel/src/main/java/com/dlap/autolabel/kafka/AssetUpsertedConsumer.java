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
import com.dlap.autolabel.service.IAutoLabelOrchestrator;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Drives the auto-labeling pipeline off
 * {@link DlapConstants.KafkaTopic#CATALOG_ASSET_UPSERTED} events
 * published by da-catalog (M3 wiring).
 *
 * <p>One asset upsert → one orchestrator call. Manual ack only on
 * success; failures fall through to the {@code .DLT} topic via
 * {@link KafkaReliabilityConfiguration} (R-P1-6).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssetUpsertedConsumer {

    private final IAutoLabelOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED,
            groupId = "da-autolabel-asset-upserted",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY
    )
    public void onAssetUpserted(String payload, Acknowledgment ack) {
        UUID assetId = extractAssetId(payload);
        if (assetId == null) {
            log.warn("Skipping asset-upserted event with no parseable assetId. Acknowledging to drain.");
            ack.acknowledge();
            return;
        }

        try {
            int applied = orchestrator.labelAsset(assetId);
            log.info("Auto-labeled asset {}: {} confident labels applied", assetId, applied);
            ack.acknowledge();
        } catch (Exception e) {
            // Fall through without ack - DefaultErrorHandler retries with
            // ExponentialBackOff and routes to the DLQ when retries exhaust.
            log.warn("Auto-label failed for asset {}: {}; will retry / DLQ", assetId, e.getMessage());
            throw new RuntimeException("Auto-label failed for asset " + assetId, e);
        }
    }

    /**
     * The producer publishes the same JSON used on the legacy
     * dlap.asset.change.event topic - an AssetChangeEvent envelope
     * with {@code assetId}, {@code eventType}, {@code timestamp},
     * {@code userId}, {@code sourceService}, {@code assetData}. Pull
     * the assetId out defensively.
     */
    private UUID extractAssetId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode idNode = node.get("assetId");
            if (idNode == null || idNode.isNull()) {
                return null;
            }
            return UUID.fromString(idNode.asText());
        } catch (Exception e) {
            log.warn("Failed to parse asset-upserted payload: {}", e.getMessage());
            return null;
        }
    }
}
