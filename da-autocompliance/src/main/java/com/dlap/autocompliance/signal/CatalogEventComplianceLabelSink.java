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

package com.dlap.autocompliance.signal;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.dlap.autocompliance.client.CatalogAssetClient;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Production {@link ComplianceLabelSink}: writes a compliance label onto a catalog asset
 * through DLAP's <b>authoritative label pipeline</b> (ADR-0035). It resolves the signal's
 * {@code assetUri} to the catalog id, then publishes an {@code addLabel} policy-action event
 * onto {@link DlapConstants.KafkaTopic#POLICY_ACTION_EVENT} - the same topic da-autolabel's
 * {@code PolicyActionLabelConsumer} already consumes to stamp labels. So externally-ingested
 * compliance labels land through the identical, decoupled, retryable path DLAP uses for all
 * labels; DLAP's catalog stays the single source of truth.
 *
 * <p>Serialization: da-autolabel consumes the value as a raw JSON String
 * (StringDeserializer). This service's producer uses JsonSerializer, so we send the
 * {@code ObjectNode} (which serializes to a JSON object) - sending a pre-serialized String
 * would double-encode it and the consumer's {@code readTree} would fail.
 */
@Component
public class CatalogEventComplianceLabelSink implements ComplianceLabelSink {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventComplianceLabelSink.class);
    private static final String ADD_LABEL_ACTION = "addLabel";
    /** The {@code source} of every event this sink publishes; also part of the fireId. */
    static final String COMPLIANCE_SOURCE = "compliance";
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 10;

    private final CatalogAssetClient catalogClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Dedupe guard: suppresses re-emitting an identical {@code assetUri|labelName} within a
     * JVM lifetime, so the periodic re-sync does not republish every active signal each run.
     * In-memory by design - after a restart each label is re-emitted once (benign: the
     * downstream {@code addLabel} is idempotent). A changed posture is a different labelName,
     * so it is a new key and is emitted. Bounded by distinct (asset, label) pairs.
     */
    private final Set<String> emitted = ConcurrentHashMap.newKeySet();

    public CatalogEventComplianceLabelSink(CatalogAssetClient catalogClient,
                                           KafkaTemplate<String, Object> kafkaTemplate,
                                           ObjectMapper objectMapper) {
        this.catalogClient = catalogClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void applyLabel(String assetUri, String labelName, String detail) {
        String dedupeKey = assetUri + "|" + labelName;
        if (emitted.contains(dedupeKey)) {
            log.debug("Compliance: addLabel '{}' for {} already emitted this lifetime; skipping",
                    labelName, assetUri);
            return;
        }

        UUID assetId = resolveAssetId(assetUri, labelName);
        if (assetId == null) {
            return;
        }
        ObjectNode event = objectMapper.createObjectNode();
        event.put("fireId", fireIdFor(assetId, labelName));
        event.put("assetId", assetId.toString());
        event.put("actionType", ADD_LABEL_ACTION);
        event.put("source", COMPLIANCE_SOURCE);
        if (detail != null && !detail.isBlank()) {
            event.put("detail", detail);
        }
        ObjectNode params = event.putObject("actionParameters");
        params.put("labelName", labelName);

        try {
            // Synchronous + checked: block on the send so a broker failure surfaces to the
            // caller (ComplianceSignalSyncService counts/logs it per-signal) rather than being
            // silently dropped on the compliance/audit path. Send the ObjectNode (not a
            // String): JsonSerializer -> raw JSON object bytes, which da-autolabel's
            // StringDeserializer consumer reads and readTree parses.
            kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, assetId.toString(), event)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            emitted.add(dedupeKey);
            log.info("Compliance: published addLabel '{}' for asset {} ({})", labelName, assetId, assetUri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted publishing compliance addLabel '" + labelName + "' for asset " + assetId, e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to publish compliance addLabel '" + labelName + "' for asset " + assetId, e);
        }
    }

    /**
     * The deterministic fireId of a compliance label: a name UUID of the asset id, the label and the
     * compliance source. Consumers of {@code dlap.policies.actions} record or apply a fire once per fireId
     * (ADR-0135), so the same label on the same asset published again (after a restart empties the in-memory
     * guard, or by a second instance) is recognised as a repeat (Q-877).
     *
     * @param assetId   the catalog asset id
     * @param labelName the compliance label
     * @return the fireId, a version 3 UUID as text
     */
    static String fireIdFor(UUID assetId, String labelName) {
        String name = assetId + "|" + labelName + "|" + COMPLIANCE_SOURCE;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private UUID resolveAssetId(String assetUri, String labelName) {
        try {
            var resolution = catalogClient.resolveAssetId(assetUri);
            if (resolution == null || resolution.assetId() == null) {
                log.warn("Compliance label '{}' skipped: assetUri {} not found in catalog", labelName, assetUri);
                return null;
            }
            return resolution.assetId();
        } catch (Exception e) {
            // FeignException.NotFound (unknown URI) or a transport error - skip this signal.
            log.warn("Compliance label '{}' skipped: could not resolve assetUri {} ({})",
                    labelName, assetUri, e.getMessage());
            return null;
        }
    }
}
