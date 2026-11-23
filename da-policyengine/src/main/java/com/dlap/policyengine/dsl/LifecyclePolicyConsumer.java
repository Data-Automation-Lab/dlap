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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.EventPathCatalogLookup;
import com.dlap.policyengine.config.KafkaReliabilityConfiguration;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Lifecycle-driven policy evaluator (ADR-0021 / P0-H): one {@code @KafkaListener} over the typed
 * lifecycle topics.
 *
 * <p>For each inbound event:
 * <ol>
 *   <li>Load the ENABLED policies and keep the parsed documents whose event triggers name this
 *       topic or its {@code lifecycle:} shorthand.</li>
 *   <li>Build the asset snapshot from the event: its {@code matchSnapshot} when present, otherwise
 *       the top-level fields and the log-spine {@code targetResource} ({@code canonicalUri} as
 *       {@code assetUri}, {@code sourceProvider} as {@code cloudProvider}, {@code kind},
 *       {@code name}, {@code region}, {@code createdTime}, {@code sizeBytes}, {@code labels} as
 *       tags).</li>
 *   <li>Identify the asset (POL-01): the event's {@code assetId}, else the catalog id of its
 *       {@code assetUri} ({@code GET /assets/resolve}). The catalog's view of the asset then
 *       fills every snapshot key the event does not carry (labels, the ADR-0046 event-time fields,
 *       read coverage, legal hold); the event's own values win, except for a policy that requires
 *       full read coverage (POL-02, Q-471): it is judged on the catalog's read coverage and the later
 *       of the two recorded reads ({@link AssetSnapshots#withCatalogCoverage}).</li>
 *   <li>Evaluate each policy with {@link PolicyDslEvaluator} on the business clock and publish a
 *       {@code PolicyActionRequest} ({@link PolicyActionRequests#forEvent}) only when the match is
 *       {@code TRUE}. An asset the catalog cannot identify is never acted on: the fire is skipped
 *       and counted in {@code policyengine.actions.skipped{reason=unresolved-asset}}.</li>
 *   <li>Route the fire through {@link ApprovalGate} (APPR-01): a destructive action, or one whose
 *       policy asks for approval, waits as an approval request instead of being published. When
 *       the request cannot be recorded the record is not acknowledged, so it is retried and then
 *       dead-lettered; a destructive fire is never published to get past a database outage.</li>
 * </ol>
 *
 * <p>Gated by {@code dlap.policyengine.dsl-consumer-enabled=true} (default true).
 */
@Component
@ConditionalOnProperty(name = "dlap.policyengine.dsl-consumer-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class LifecyclePolicyConsumer {

    /** Counter of matched fires that published nothing, tagged by {@code reason}. */
    static final String SKIPPED_COUNTER = "policyengine.actions.skipped";

    private final PolicyRepository policyRepository;
    private final PolicyDslParser parser;
    private final PolicyDslEvaluator evaluator;
    private final ApprovalGate gate;
    private final ObjectMapper objectMapper;
    private final EventPathCatalogLookup catalog;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the consumer.
     *
     * @param policyRepository policy store
     * @param parser           DSL parser
     * @param evaluator        DSL evaluator
     * @param gate             approval gate in front of the action producer
     * @param objectMapper     JSON mapper for the payload
     * @param catalog          catalog lookups for asset identity and lifecycle fields, cached for a short
     *                         time ({@link EventPathCatalogLookup}, Q-437)
     * @param clock            business clock
     * @param meterRegistry    metrics
     */
    public LifecyclePolicyConsumer(PolicyRepository policyRepository, PolicyDslParser parser,
            PolicyDslEvaluator evaluator, ApprovalGate gate, ObjectMapper objectMapper,
            EventPathCatalogLookup catalog, Clock clock, MeterRegistry meterRegistry) {
        this.policyRepository = policyRepository;
        this.parser = parser;
        this.evaluator = evaluator;
        this.gate = gate;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Evaluates the event against every policy with a matching event trigger.
     *
     * @param payload the event JSON
     * @param topic   the topic it arrived on
     * @param ack     manual acknowledgment
     */
    @KafkaListener(
            topics = {
                    DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED,
                    DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED,
                    DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED,
                    DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED,
                    DlapConstants.KafkaTopic.DISCOVERY_ASSET_PERMISSION_CHANGED,
                    DlapConstants.KafkaTopic.DISCOVERY_ASSET_ARCHIVED
            },
            groupId = "da-policyengine-dsl-consumer",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onLifecycleEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment ack) {
        try {
            Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<>() { });
            int matched = evaluate(event, topic);
            if (matched > 0) {
                log.info("Lifecycle event on {} matched {} policies", topic, matched);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process lifecycle event on topic {}: {}", topic, e.getMessage());
            throw new RuntimeException("lifecycle policy evaluation failed", e);
        }
    }

    /**
     * Evaluates one parsed event.
     *
     * @param event the event map
     * @param topic the topic it arrived on
     * @return how many policies matched and fired
     */
    int evaluate(Map<String, Object> event, String topic) {
        String lifecycle = lifecycleFromTopic(topic);
        List<Candidate> candidates = new ArrayList<>();
        for (Policy p : policyRepository.findByStatus(PolicyStatus.ENABLED)) {
            // ADR-0031: the canonical DSL lives in policyDocument; description is the v0.1.0 fallback.
            String yaml = p.getPolicyDocument();
            if (yaml == null || yaml.isBlank()) {
                yaml = p.getDescription();
            }
            if (yaml == null || yaml.isBlank()) {
                continue;
            }
            try {
                PolicyDsl dsl = parser.parse(yaml);
                if (PolicyDsl.KIND_POLICY.equals(dsl.getKind()) && triggersInclude(dsl, topic, lifecycle)) {
                    candidates.add(new Candidate(p.getId(), dsl));
                }
            } catch (PolicyDslParser.PolicyDslParseException pe) {
                log.warn("Policy {} ({}) has invalid YAML: {}", p.getId(), p.getName(), pe.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<String, Object> snapshot = extractSnapshot(event);
        Optional<UUID> assetId = identify(event, snapshot);
        Map<String, Object> catalogView = assetId.flatMap(catalog::getAssetById)
                .map(AssetSnapshots::fromCatalogAsset).orElse(null);
        AssetSnapshots.fillGaps(snapshot, catalogView);
        assetId.ifPresent(id -> snapshot.put(AssetSnapshots.ASSET_ID, id.toString()));
        Map<String, Object> coverageSnapshot = null;
        // One WARN per asset per event for a FULL coverage without an observation time (Q-475).
        Set<String> coverageWarned = new HashSet<>();

        Instant now = clock.instant();
        String sourceEventId = AssetSnapshots.firstText(event, "eventId");
        int matched = 0;
        DataAccessException approvalFailure = null;
        for (Candidate c : candidates) {
            try {
                Map<String, Object> judged = snapshot;
                if (PolicyDslEvaluator.requiresFullReadCoverage(c.dsl())) {
                    if (coverageSnapshot == null) {
                        coverageSnapshot = AssetSnapshots.withCatalogCoverage(snapshot, catalogView);
                    }
                    judged = coverageSnapshot;
                }
                MatchResult result = evaluator.evaluate(c.dsl(), judged, evaluator.options(now, c.dsl(), coverageWarned));
                if (result.isUnknown()) {
                    log.debug("Policy {} unknown for the event on {} (clauses {})",
                            c.dsl().getMetadata().getId(), topic, result.unknownClauses());
                }
                if (!result.isTrue()) {
                    continue;
                }
                if (assetId.isEmpty()) {
                    log.warn("Policy {} matched an event on {} for an asset the catalog cannot identify; "
                            + "no action published", c.dsl().getMetadata().getId(), topic);
                    meterRegistry.counter(SKIPPED_COUNTER, "reason", "unresolved-asset").increment();
                    continue;
                }
                ApprovalGate.Route route = gate.submit(PolicyActionRequests.forEvent(c.dsl(), c.policyDbId(),
                        judged, lifecycle, sourceEventId, now));
                log.info("Policy fired: id={} action={} asset={} route={}", c.dsl().getMetadata().getId(),
                        c.dsl().getSpec().getAction().getType(), assetId.get(), route);
                matched++;
            } catch (DataAccessException e) {
                // The approval request could not be recorded: retry the record, never drop the fire.
                log.warn("Policy {} matched an event on {} but its approval request could not be recorded: {}",
                        c.policyDbId(), topic, e.getMessage());
                approvalFailure = e;
            } catch (RuntimeException e) {
                log.warn("Error evaluating policy {} for event on {}: {}", c.policyDbId(), topic, e.getMessage());
            }
        }
        if (approvalFailure != null) {
            throw approvalFailure;
        }
        return matched;
    }

    /** The asset's catalog id: the event's own {@code assetId}, else the id of its URI. */
    private Optional<UUID> identify(Map<String, Object> event, Map<String, Object> snapshot) {
        String raw = AssetSnapshots.firstText(event, "assetId", "targetAssetId");
        if (raw == null) {
            raw = AssetSnapshots.firstText(snapshot, AssetSnapshots.ASSET_ID);
        }
        if (raw != null) {
            try {
                return Optional.of(UUID.fromString(raw.strip()));
            } catch (IllegalArgumentException e) {
                log.debug("Event assetId is not a catalog UUID; resolving its URI instead");
            }
        }
        return catalog.resolveAssetIdByUri(AssetSnapshots.firstText(snapshot, AssetSnapshots.ASSET_URI));
    }

    /**
     * The asset snapshot an event carries: its {@code matchSnapshot} when present, else the
     * top-level fields and the log-spine {@code targetResource}.
     *
     * @param event the event map
     * @return a mutable snapshot of the known values
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> extractSnapshot(Map<String, Object> event) {
        Object snap = event.get("matchSnapshot");
        if (snap instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (String key : List.of(AssetSnapshots.ASSET_URI, AssetSnapshots.CLOUD_PROVIDER, AssetSnapshots.SERVICE_TYPE,
                AssetSnapshots.ASSET_TYPE, AssetSnapshots.ASSET_NAME, AssetSnapshots.NAME, AssetSnapshots.REGION,
                AssetSnapshots.LABELS, AssetSnapshots.TAGS, AssetSnapshots.LAST_ACCESSED_AT,
                AssetSnapshots.CLOUD_CREATED_AT, AssetSnapshots.SIZE_BYTES, AssetSnapshots.READ_COVERAGE)) {
            copyIfPresent(event, key, flat);
        }
        Object provider = event.get("sourceProvider");
        if (provider != null && !"SOURCE_PROVIDER_UNSPECIFIED".equals(provider.toString())) {
            flat.putIfAbsent(AssetSnapshots.CLOUD_PROVIDER, provider.toString());
        }
        // The DlapLogEvent targetResource (log-event.md section 4.2).
        if (event.get("targetResource") instanceof Map<?, ?> tm) {
            putText(flat, AssetSnapshots.ASSET_URI, tm.get("canonicalUri"));
            putText(flat, AssetSnapshots.REGION, tm.get("region"));
            putText(flat, AssetSnapshots.ASSET_TYPE, tm.get("kind"));
            putText(flat, AssetSnapshots.ASSET_NAME, tm.get("name"));
            putText(flat, AssetSnapshots.CLOUD_CREATED_AT, tm.get("createdTime"));
            putText(flat, AssetSnapshots.SIZE_BYTES, tm.get("sizeBytes"));
            if (tm.get("labels") instanceof Map<?, ?> tags && !tags.isEmpty()) {
                flat.putIfAbsent(AssetSnapshots.TAGS, tags);
            }
        }
        return flat;
    }

    private static void putText(Map<String, Object> dst, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            dst.putIfAbsent(key, value);
        }
    }

    private static void copyIfPresent(Map<String, Object> src, String key, Map<String, Object> dst) {
        if (src.get(key) != null) {
            dst.put(key, src.get(key));
        }
    }

    /**
     * Maps a typed lifecycle topic name back to its {@code lifecycle:} shorthand.
     *
     * @param topic the topic
     * @return the shorthand, or {@code null} for another topic
     */
    static String lifecycleFromTopic(String topic) {
        if (topic == null) {
            return null;
        }
        return switch (topic) {
            case DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED -> "created";
            case DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED -> "modified";
            case DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED -> "deleted";
            case DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED -> "accessed";
            case DlapConstants.KafkaTopic.DISCOVERY_ASSET_PERMISSION_CHANGED -> "permission-changed";
            case DlapConstants.KafkaTopic.DISCOVERY_ASSET_ARCHIVED -> "archived";
            default -> null;
        };
    }

    /** True if any event-trigger in the policy targets this topic or its lifecycle shorthand. */
    private static boolean triggersInclude(PolicyDsl dsl, String topic, String lifecycle) {
        if (dsl.getSpec() == null || dsl.getSpec().getTriggers() == null) {
            return false;
        }
        for (PolicyDsl.Trigger t : dsl.getSpec().getTriggers()) {
            if (t == null || !"event".equalsIgnoreCase(t.getType())) {
                continue;
            }
            if (topic.equals(t.getTopic())) {
                return true;
            }
            if (lifecycle != null && lifecycle.equalsIgnoreCase(t.getLifecycle())) {
                return true;
            }
        }
        return false;
    }

    /** A policy whose event trigger names the inbound topic. */
    private record Candidate(UUID policyDbId, PolicyDsl dsl) {
    }
}
