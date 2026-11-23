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

package com.dlap.catalog.kafka;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.catalog.idempotency.ProcessedEventStore;
import com.dlap.catalog.lineage.LineageExtractor;
import com.dlap.catalog.lineage.LineageExtractor.LineageEdge;
import com.dlap.catalog.lineage.MetadataHarvester;
import com.dlap.catalog.service.LineageService;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/**
 * Lineage and harvested business metadata from the normalized log stream, for
 * {@link LogEventEnrichmentListener}. Runs inside the listener's transaction; nothing is
 * swallowed, so a failure rolls the whole event back and the error handler retries it and
 * then dead-letters it (ADR-0055).
 *
 * <ul>
 *   <li><b>Lineage</b>: endpoints are normalized and matched like event targets, so an edge
 *       finds rows stored canonically and rows stored in older forms; the edges of one event
 *       into one downstream row are applied once ({@code catalog-lineage} claim). Failed or
 *       pending operations moved no data and give no edges.</li>
 *   <li><b>Harvest</b>: only successful events (or crawler observations) on the event's own
 *       target (never a rolled-up container) harvest metadata, once per event and row
 *       ({@code catalog-enrichment} claim). The harvested values are those of the latest event
 *       by event time ({@code harvestedEventTime}), not of the last event processed.</li>
 * </ul>
 */
@Component
public class LogEventEnricher {

    private static final Logger log = LoggerFactory.getLogger(LogEventEnricher.class);

    /** Nested sub-map under {@code businessMetadataJson} that isolates log-harvested keys from manual ones. */
    static final String HARVESTED_KEY = "harvested";
    /** Event time of the event whose values the harvested map holds (asset-descriptor.md). */
    static final String HARVESTED_EVENT_TIME = "harvestedEventTime";

    private final CatalogEventDecoder decoder;
    private final AssetMatcher matcher;
    private final ProcessedEventStore processed;
    private final LineageService lineageService;
    private final LineageExtractor lineageExtractor;
    private final MetadataHarvester metadataHarvester;
    private final CatalogIngestProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates the enricher.
     *
     * @param decoder           normalizes lineage endpoints
     * @param matcher           finds rows
     * @param processed         the processed-event claims
     * @param lineageService    persists lineage edges
     * @param lineageExtractor  derives edges from an event
     * @param metadataHarvester derives owner, team, region, provider and account
     * @param properties        governed prefixes
     */
    public LogEventEnricher(CatalogEventDecoder decoder, AssetMatcher matcher, ProcessedEventStore processed,
                            LineageService lineageService, LineageExtractor lineageExtractor,
                            MetadataHarvester metadataHarvester, CatalogIngestProperties properties) {
        this.decoder = decoder;
        this.matcher = matcher;
        this.processed = processed;
        this.lineageService = lineageService;
        this.lineageExtractor = lineageExtractor;
        this.metadataHarvester = metadataHarvester;
        this.properties = properties;
    }

    /**
     * Waits for the normalized-URI index outside any transaction ({@link AssetMatcher#awaitIndex}).
     * The listener calls it before {@link #enrich}.
     */
    public void awaitMatchIndex() {
        matcher.awaitIndex();
    }

    /**
     * Enriches the catalog from one event.
     *
     * @param event   the decoded event
     * @param payload the record value, for lineage keys outside the log contract
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enrich(CatalogEvent event, String payload) {
        extractLineage(event, payload);
        harvestMetadata(event);
    }

    private void extractLineage(CatalogEvent event, String payload) {
        ResultStatus status = event.event().getResultStatus();
        if (status == ResultStatus.FAILURE || status == ResultStatus.PENDING) {
            return;
        }
        List<LineageEdge> edges = lineageExtractor.extract(lineageInput(event, payload));
        if (edges.isEmpty()) {
            return;
        }
        SourceProvider provider = event.event().getSourceProvider();
        Map<UUID, List<ResolvedEdge>> byDownstream = new LinkedHashMap<>();
        for (LineageEdge edge : edges) {
            Optional<Endpoint> up = resolve(edge.upstreamUri(), provider);
            Optional<Endpoint> down = resolve(edge.downstreamUri(), provider);
            if (up.isEmpty() || down.isEmpty()) {
                log.debug("Lineage edge skipped: endpoint not catalogued (upstreamFound={}, downstreamFound={})",
                        up.isPresent(), down.isPresent());
                continue;
            }
            byDownstream.computeIfAbsent(down.get().assetId(), k -> new ArrayList<>())
                    .add(new ResolvedEdge(up.get().storedUri(), down.get().storedUri(), edge));
        }
        Instant eventTime = event.eventTime().orElse(null);
        for (Map.Entry<UUID, List<ResolvedEdge>> entry : byDownstream.entrySet()) {
            if (!processed.claim(ProcessedEventStore.CONSUMER_LINEAGE, event.eventKey(), entry.getKey(), eventTime)) {
                continue;
            }
            for (ResolvedEdge e : entry.getValue()) {
                lineageService.upsertEdgeByUri(e.upstreamUri(), e.downstreamUri(), e.edge().relationshipType(),
                        e.edge().details());
            }
        }
    }

    private void harvestMetadata(CatalogEvent event) {
        if (!event.mayChangeState() || !event.isProven()) {
            return;
        }
        Optional<UUID> assetId = directRow(event);
        if (assetId.isEmpty()) {
            return;
        }
        Instant eventTime = event.eventTime().orElseThrow();
        Map<String, Object> harvested = metadataHarvester.harvest(harvestInput(event.event()));
        if (harvested.isEmpty()) {
            return;
        }
        Asset asset = entityManager.find(Asset.class, assetId.get(), LockModeType.PESSIMISTIC_WRITE);
        if (asset == null
                || !processed.claim(ProcessedEventStore.CONSUMER_ENRICHMENT, event.eventKey(), asset.getAssetId(),
                        eventTime)) {
            return;
        }
        Map<String, Object> business = asset.getBusinessMetadataJson() == null
                ? new HashMap<>() : new HashMap<>(asset.getBusinessMetadataJson());
        Map<String, Object> bucket = business.get(HARVESTED_KEY) instanceof Map<?, ?> m
                ? copy(m) : new HashMap<>();
        Instant previous = parseInstant(bucket.get(HARVESTED_EVENT_TIME));
        if (previous != null && eventTime.isBefore(previous)) {
            log.debug("Harvest from an older event skipped for asset {}", asset.getAssetId());
            return;
        }
        bucket.putAll(harvested);
        bucket.put(HARVESTED_EVENT_TIME, eventTime.toString());
        business.put(HARVESTED_KEY, bucket);
        asset.setBusinessMetadataJson(business);

        // Backfill the first-class ownerInfo column only when it is empty, so a manual owner is
        // never overwritten by a log-derived one.
        Object owner = harvested.get("harvestedOwner");
        if (owner != null && (asset.getOwnerInfo() == null || asset.getOwnerInfo().isBlank())) {
            asset.setOwnerInfo(owner.toString());
        }
    }

    /** The event's own target row: never the container an object rolls up to. */
    private Optional<UUID> directRow(CatalogEvent event) {
        Optional<CanonicalAssetId> canonical = event.canonicalTarget();
        if (canonical.isPresent()) {
            CanonicalAssetId id = canonical.get();
            if (id.isObjectLevel() && !properties.isGoverned(id)) {
                return Optional.empty();
            }
            return matcher.find(id).map(AssetMatcher.Match::assetId);
        }
        if (event.target() == null) {
            return Optional.empty();
        }
        String provider = CatalogEvent.UNKNOWN_PROVIDER.equals(event.providerTag()) ? null : event.providerTag();
        return matcher.findRaw(event.rawTargetId(), provider).map(AssetMatcher.Match::assetId);
    }

    /** A lineage endpoint as a catalog row and the URI stored for it. */
    private Optional<Endpoint> resolve(String uri, SourceProvider provider) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        AssetIdParseResult r = decoder.normalizeReference(uri, provider);
        Optional<UUID> id = r.id().flatMap(matcher::find).map(AssetMatcher.Match::assetId);
        if (id.isEmpty() && !r.isNormalized()) {
            String p = provider == SourceProvider.SOURCE_PROVIDER_UNSPECIFIED || provider == SourceProvider.UNRECOGNIZED
                    ? null : provider.name();
            id = matcher.findRaw(uri, p).map(AssetMatcher.Match::assetId);
        }
        return id.flatMap(assetId -> matcher.storedUri(assetId).map(stored -> new Endpoint(assetId, stored)));
    }

    /**
     * The extractor's input: the decoded event as a wire map, plus top-level keys outside the
     * contract that the extractor also reads ({@code sourceResources}, {@code query},
     * {@code jobUri}), and the producer's own {@code rawEvent} object, which keeps exact numbers.
     */
    Map<String, Object> lineageInput(CatalogEvent event, String payload) {
        Map<String, Object> input = decoder.toMap(event.event());
        Map<String, Object> raw = rawObject(payload, event.eventKey());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (!input.containsKey(e.getKey()) || ("rawEvent".equals(e.getKey()) && e.getValue() instanceof Map)) {
                input.put(e.getKey(), e.getValue());
            }
        }
        return input;
    }

    /** The harvester's input, from contract fields only; unspecified enums are left out. */
    static Map<String, Object> harvestInput(DlapLogEvent event) {
        Map<String, Object> input = new LinkedHashMap<>();
        Principal p = event.getPrincipal();
        Map<String, Object> principal = new LinkedHashMap<>();
        putIfText(principal, "id", p.getId());
        putIfText(principal, "displayName", p.getDisplayName());
        putIfText(principal, "accountId", p.getAccountId());
        if (p.getType() != PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED && p.getType() != PrincipalType.UNRECOGNIZED) {
            principal.put("type", p.getType().name());
        }
        if (!principal.isEmpty()) {
            input.put("principal", principal);
        }
        Map<String, Object> target = new LinkedHashMap<>();
        putIfText(target, "region", event.getTargetResource().getRegion());
        putIfText(target, "project", event.getTargetResource().getProject());
        input.put("targetResource", target);
        SourceProvider provider = event.getSourceProvider();
        if (provider != SourceProvider.SOURCE_PROVIDER_UNSPECIFIED && provider != SourceProvider.UNRECOGNIZED) {
            input.put("sourceProvider", provider.name());
        }
        return input;
    }

    private Map<String, Object> rawObject(String payload, String eventKey) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            for (int i = 0; i < 2 && node != null && node.isTextual(); i++) {
                node = objectMapper.readTree(node.asText());
            }
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            // The codec already accepted the payload, so a second parse failing is not expected.
            // Lineage then reads the contract fields only. Jackson messages can quote payload
            // text, so only the exception type is logged.
            log.warn("Raw payload of event {} did not parse as JSON a second time ({}); lineage uses "
                    + "the contract fields only", eventKey, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static void putIfText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<?, ?> m) {
        return new HashMap<>((Map<String, Object>) m);
    }

    private static Instant parseInstant(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record Endpoint(UUID assetId, String storedUri) {
    }

    private record ResolvedEdge(String upstreamUri, String downstreamUri, LineageEdge edge) {
    }
}
