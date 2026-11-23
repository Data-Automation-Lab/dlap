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

package com.dlap.autocompliance.kafka;

import com.dlap.autocompliance.config.KafkaReliabilityConfiguration;
import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import com.dlap.autocompliance.service.IComplianceService;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Group D: drives real compliance evaluation off the
 * {@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_PERMISSION_CHANGED}
 * topic. The GCP demo seeder ({@code da-logingest DemoEventSeeder})
 * emits PERMISSION-change events through the log spine, so this topic
 * carries traffic without real cloud credentials.
 *
 * <p>Each event identifies a single asset whose IAM bindings changed.
 * We submit that asset to the {@code IAM-DRIFT-001} control via the
 * existing {@link IComplianceService#evaluateControl} facade, which
 * persists a {@code ControlEvaluationJobEntity} row and runs the
 * findings-backed pass/fail logic. This is the wiring that turns the
 * permission-change stream into durable compliance evaluation data.
 *
 * <p>The payload is a normalized {@code DlapLogEvent} serialized as
 * proto3-JSON (camelCase). We mirror the defensive nested-then-flat
 * field access used by {@code da-catalog AssetLifecycleEventListener}:
 * the asset identifier is at {@code targetResource.id} with flat
 * {@code targetResourceId} / {@code assetUri} fallbacks, and the
 * provider is at {@code sourceProvider}.
 *
 * <p>Reliability mirrors the other Group consumers
 * ({@code da-autodelete/da-autoarchival PolicyActionEventListener}):
 * manual acknowledgment via {@link KafkaReliabilityConfiguration#MANUAL_ACK_FACTORY},
 * {@code ack.acknowledge()} on success and on intentional skip, and a
 * re-throw on hard failure so the container's {@code DefaultErrorHandler}
 * runs the backoff and routes to the {@code .DLT} topic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionChangeEventListener {

    /** Control evaluated for every permission-change event. */
    private static final String IAM_DRIFT_CONTROL_ID = "IAM-DRIFT-001";

    private final ObjectMapper objectMapper;
    private final IComplianceService complianceService;

    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_PERMISSION_CHANGED,
            groupId = "da-autocompliance-permission-changes",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onPermissionChanged(String payload, Acknowledgment ack) {
        if (payload == null || payload.isBlank()) {
            log.warn("Null/blank permission-change payload; acknowledging to drain");
            ack.acknowledge();
            return;
        }

        try {
            Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<>() { });

            String assetId = readAssetIdentifier(event);
            if (assetId == null || assetId.isBlank()) {
                log.warn("Permission-change event missing targetResource.id; ack and skip");
                ack.acknowledge();
                return;
            }

            String provider = readProvider(event);
            if (provider != null && !"GCP".equalsIgnoreCase(provider)) {
                // v0.1.0 ships GCP only; non-GCP providers have no IAM-drift
                // evaluation path yet. Skip and ack rather than DLQ.
                log.debug("Permission-change for non-GCP provider {} (asset {}); skipping",
                        provider, assetId);
                ack.acknowledge();
                return;
            }

            ControlEvaluationRequestDTO request = ControlEvaluationRequestDTO.builder()
                    .targetAssetIds(List.of(assetId))
                    .triggeredBy("da-autocompliance-permission-changes")
                    .build();

            ControlEvaluationResponseDTO response =
                    complianceService.evaluateControl(IAM_DRIFT_CONTROL_ID, request);

            log.info("IAM-drift evaluation for asset {} -> job {} status {} ({})",
                    assetId,
                    response != null ? response.getJobId() : null,
                    response != null ? response.getStatus() : null,
                    response != null ? response.getMessage() : null);

            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Permission-change evaluation failed for payload: {}", e.getMessage());
            // Re-throw so the DefaultErrorHandler runs the backoff and DLQs.
            throw new IllegalStateException("permission-change evaluation failed", e);
        }
    }

    /**
     * {@code DlapLogEvent.targetResource.id} per spec. Tolerates flat
     * legacy payloads with top-level {@code targetResourceId} or
     * {@code assetUri}. Mirrors
     * {@code da-catalog AssetLifecycleEventListener#readAssetIdentifier}.
     */
    @SuppressWarnings("unchecked")
    private static String readAssetIdentifier(Map<String, Object> event) {
        Object target = event.get("targetResource");
        if (target instanceof Map<?, ?> m) {
            Object id = m.get("id");
            if (id != null) {
                return id.toString();
            }
        }
        Object flat = event.get("targetResourceId");
        if (flat != null) {
            return flat.toString();
        }
        Object uri = event.get("assetUri");
        if (uri != null) {
            return uri.toString();
        }
        return null;
    }

    /** {@code DlapLogEvent.sourceProvider} (flat, e.g. {@code "GCP"}). */
    private static String readProvider(Map<String, Object> event) {
        Object provider = event.get("sourceProvider");
        return provider != null ? provider.toString() : null;
    }
}
