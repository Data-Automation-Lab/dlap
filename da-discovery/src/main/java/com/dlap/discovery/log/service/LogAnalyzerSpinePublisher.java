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

package com.dlap.discovery.log.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.dlap.common.constants.DlapConstants;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.logspine.SpineEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Publishes log-analyzer {@link ResourceChange}s onto the normalized log-event spine
 * in the <em>canonical flat {@code DlapLogEvent} shape</em> - the single wire contract
 * on {@link DlapConstants.KafkaTopic#LOG_EVENT_NORMALIZED} that
 * {@code DiscoveryLogEventProcessor} (router) and da-catalog's listeners consume.
 *
 * <p><b>Why this exists.</b> Analyzers historically published a
 * {@code LogEvent{ payload: @class ResourceChange }} - {@code changeType} nested under
 * {@code payload}, with the fully-qualified Java class name embedded on the wire. The
 * router reads a <em>top-level</em> {@code changeType} (and da-catalog reads
 * {@code targetResource.id}), so those events dead-lettered and never reached the catalog.
 * This publisher emits the same flat shape {@code CrawlerAssetSpineBridge} produces, so the
 * log-analyzer path and the crawler path converge on ONE contract - the router stays a thin
 * router (no per-producer format tolerance) and the {@code @class}-on-the-wire coupling is
 * gone. New producers target this shape; they do not teach the consumer new formats.
 *
 * <p>Wire form (SPINE-01): the flat tree built here is printed once by {@link SpineEvents#encode}
 * (canonical proto3 JSON through {@code DlapLogEventCodec}) and sent on the String-valued template,
 * so the record value is the JSON object and not a JSON string holding it. The send is awaited:
 * {@link #publish} throws {@code SpinePublishException} when the broker does not acknowledge the
 * event, so the caller learns of the loss instead of finding it in a callback log line.
 *
 * <p>At-least-once: duplicates are benign, because the catalog upsert is keyed by asset URI.
 */
@Component
public class LogAnalyzerSpinePublisher {

    private static final Logger log = LoggerFactory.getLogger(LogAnalyzerSpinePublisher.class);

    /** Marks these events as log-analyzer-synthesized (mirrors CrawlerAssetSpineBridge's "crawler"). */
    static final String SOURCE_LOG_ANALYZER = "loganalyzer";
    /** Operation carried on the synthetic event so downstream harvest can attribute it. */
    static final String OPERATION_DISCOVERY = "DISCOVERY";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration publishTimeout;

    /**
     * Creates the publisher.
     *
     * @param kafkaTemplate  the String-valued template ({@code stringKafkaTemplate}): the event goes
     *                       out as the JSON object itself, not wrapped by a JSON serializer
     * @param objectMapper   builds the event tree
     * @param publishTimeout how long {@link #publish} waits for the broker's acknowledgement
     */
    @Autowired
    public LogAnalyzerSpinePublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                     @Value("${" + SpineEvents.PUBLISH_TIMEOUT_PROPERTY + ":30s}")
                                     Duration publishTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.publishTimeout = publishTimeout;
    }

    /**
     * Creates the publisher with the default acknowledgement timeout.
     *
     * @param kafkaTemplate the String-valued template
     * @param objectMapper  builds the event tree
     */
    public LogAnalyzerSpinePublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this(kafkaTemplate, objectMapper, SpineEvents.DEFAULT_TIMEOUT);
    }

    /**
     * Builds the canonical flat {@code DlapLogEvent} for a detected change and publishes it.
     *
     * @param provider  the cloud provider (e.g. {@code "GCP"}); used as {@code sourceProvider}
     * @param accountId the account/project/subscription id (diagnostic only)
     * @param change    the detected resource change
     */
    public void publish(String provider, String accountId, ResourceChange change) {
        if (change == null || change.getChangeType() == null) {
            log.warn("Not bridging null change / changeType onto the spine (provider={}, account={})",
                    provider, accountId);
            return;
        }
        String assetUri = change.getResourceId();
        if (assetUri == null || assetUri.isBlank()) {
            // Without a stable identity the catalog upsert drops the event as "missing
            // assetUri"; skip rather than emit an un-actionable record. Log only stable
            // identifiers, never the whole change (avoids leaking metadata - CLAUDE.md §11).
            log.warn("Log-analyzer change has no resourceId; not bridging to spine. provider={} changeType={}",
                    provider, change.getChangeType());
            return;
        }

        String changeType = change.getChangeType().name();

        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", change.getId() != null
                ? change.getId().toString()
                : assetUri + ":" + changeType + ":" + tsString(change));
        node.put("changeType", changeType);
        node.put("source", SOURCE_LOG_ANALYZER);
        node.put("operation", OPERATION_DISCOVERY);
        putIfPresent(node, "sourceProvider", provider);
        putIfPresent(node, "eventTime", tsString(change));

        // targetResource carries asset identity in the shape da-catalog's listener
        // (toAssetDescriptor) reads: id -> assetUri, kind -> serviceType, region.
        ObjectNode target = node.putObject("targetResource");
        target.put("id", assetUri);
        putIfPresent(target, "kind", change.getTypeId());
        putIfPresent(target, "region", detail(change, "region"));
        // The canonical asset id (ADR-0047) is the join key with the catalog. The analyzer's own
        // identifier stays in id; the canonical form is added when the change names an asset kind
        // and the identifier normalizes with hints from the change (provider, the connection's
        // account or project, region). Never guessed: an identifier that does not normalize
        // is published without one.
        CrawlerAssetIds.resolveRaw(provider, change.getTypeId(), assetUri,
                        firstNonBlank(change.getProjectId(), accountId), detail(change, "region"))
                .ifPresent(id -> target.put("canonicalUri", id.uri()));

        // Print once in the single wire encoding, then send and wait for the acknowledgement.
        // Partition by asset identity so all events for one asset stay causally ordered.
        String json = SpineEvents.encode(node);
        SpineEvents.sendAndAwait(kafkaTemplate, assetUri, json, publishTimeout);
        log.debug("Bridged {} log-analyzer event for {} onto the log-event spine", changeType, assetUri);
    }

    private static String tsString(ResourceChange change) {
        return change.getTimestamp() != null ? change.getTimestamp().toString() : null;
    }

    private static String detail(ResourceChange change, String key) {
        return change.getDetails() != null ? change.getDetails().get(key) : null;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
