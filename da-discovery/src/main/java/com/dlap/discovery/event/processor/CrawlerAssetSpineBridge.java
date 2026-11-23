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

package com.dlap.discovery.event.processor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.common.logevent.SourceFormats;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.event.service.IEventSubscriber;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.logspine.SpineEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Bridges crawler {@link ResourceEvent}s onto the normalized log-event spine
 * (ADR-0021) so that <em>discovered</em> resources become first-class catalog
 * assets - not just rows in da-discovery's internal inventory.
 *
 * <p><b>Why this exists.</b> Crawlers publish {@link ResourceEvent}s that
 * {@link CatalogResourceEventProcessor} persists into da-discovery's own
 * {@code cloud_resource} table. That table is the discovery inventory; it is
 * <em>not</em> the shared {@code dlap_assets} catalog. The only writer of
 * {@code dlap_assets} is the log-event spine: {@code DiscoveryLogEventProcessor}
 * consumes {@link DlapConstants.KafkaTopic#LOG_EVENT_NORMALIZED}, routes
 * {@code CREATE}/{@code UPDATE}/{@code DELETE} to the typed lifecycle topics, and
 * da-catalog's listeners upsert the asset. ADR-0021 anticipated "crawlers that
 * emit synthetic {@code DlapLogEvent}s with {@code source=crawler}" - but no
 * component actually did so, so crawler-only providers (Unity Catalog, which has
 * no cloud audit-log source) were discovered yet never reached the catalog.
 *
 * <p>This subscriber closes that gap for <em>every</em> provider by translating
 * each crawler event into the minimal normalized-log shape the spine understands
 * and publishing it to {@code LOG_EVENT_NORMALIZED}. Because that is the same
 * topic da-catalog's {@code LogEventEnrichmentListener} consumes, the synthetic
 * event also drives lineage/metadata harvest automatically - the "ingesting
 * assets builds lineage" property, at discovery time.
 *
 * <p><b>Identity, time and size (ADR-0045, ADR-0046, ADR-0047).</b> Besides the
 * crawler's own identifier in {@code targetResource.id}, the event carries the
 * canonical asset URI ({@code canonicalUri}, from {@link CrawlerAssetIds}), the
 * provider creation time ({@code createdTime}) and the observed size
 * ({@code sizeBytes}, present only when the crawler observed one: unknown is not
 * zero). The event id is computed from the crawler event's id with
 * {@link LogEventIds} (source kind {@code DLAP_CRAWLER}), and {@code kind} is the
 * DLAP service type when the resource resolves to an asset kind.
 *
 * <p><b>Wire form (SPINE-01).</b> The event goes out in the single encoding of log-event.md
 * section 2: {@link SpineEvents#encode} prints the flat tree built here once through
 * {@code DlapLogEventCodec} (the {@code source} marker becomes {@code origin} and
 * {@code source_format}), and the text is sent on the String-valued template, so the record value
 * is the JSON object and not a JSON string holding it. The send is awaited: when the broker does
 * not acknowledge the event, {@link #process} throws {@code SpinePublishException} instead of
 * logging the failure from a callback after the crawler event was acknowledged.
 *
 * <p>Stateless and Kafka-only; the internal-inventory write is left to
 * {@link CatalogResourceEventProcessor} (priority 10). This runs after it
 * (priority 20). Gated by {@code dlap.discovery.catalog-bridge-enabled}
 * (default on) so a deployment can suppress catalog population from crawls.
 */
@Component
@ConditionalOnProperty(
        name = "dlap.discovery.catalog-bridge-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CrawlerAssetSpineBridge implements IEventSubscriber<ResourceEvent> {

    private static final Logger log = LoggerFactory.getLogger(CrawlerAssetSpineBridge.class);

    /** Marks these events as crawler-synthesized, per ADR-0021. */
    private static final String SOURCE_CRAWLER = "crawler";
    /** Source kind of the native event key (log-event.md section 5.2, {@code DLAP_CRAWLER}). */
    private static final String SOURCE_KIND = SourceFormats.sourceKind(SourceFormats.DLAP_CRAWLER).orElseThrow();
    /** Operation carried on the synthetic event so downstream harvest can attribute it. */
    private static final String OPERATION_DISCOVERY = "DISCOVERY";
    /** Counter incremented once per asset bridged onto the spine (the central publish point). */
    private static final String DISCOVERY_ASSETS_COUNTER = "dlap.discovery.assets.total";
    /**
     * Counter of bridged assets that did not resolve to a canonical id (a virtual machine, an
     * identifier that needs a hint the connection lacks). Prometheus name:
     * {@code dlap_discovery_assets_unresolved_total}.
     */
    static final String DISCOVERY_UNRESOLVED_COUNTER = "dlap.discovery.assets.unresolved";
    /** Fallback tag value when provider/serviceType is unknown - keeps cardinality bounded. */
    private static final String TAG_UNKNOWN = "unknown";
    /** Placeholder for a missing part of the fallback native key. */
    private static final String NONE = "none";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Duration publishTimeout;

    /**
     * Creates the bridge.
     *
     * @param kafkaTemplate  the String-valued template ({@code stringKafkaTemplate}): the event goes
     *                       out as the JSON object itself, not wrapped by a JSON serializer
     * @param objectMapper   builds the event tree
     * @param meterRegistry  counts the bridged assets
     * @param publishTimeout how long {@link #process} waits for the broker's acknowledgement
     */
    @Autowired
    public CrawlerAssetSpineBridge(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry,
                                   @Value("${" + SpineEvents.PUBLISH_TIMEOUT_PROPERTY + ":30s}")
                                   Duration publishTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.publishTimeout = publishTimeout;
    }

    /**
     * Creates the bridge with the default acknowledgement timeout.
     *
     * @param kafkaTemplate the String-valued template
     * @param objectMapper  builds the event tree
     * @param meterRegistry counts the bridged assets
     */
    public CrawlerAssetSpineBridge(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this(kafkaTemplate, objectMapper, meterRegistry, SpineEvents.DEFAULT_TIMEOUT);
    }

    @Override
    public boolean canProcess(ResourceEvent event) {
        if (event == null || event.getPayload() == null || event.getChangeType() == null) {
            return false;
        }
        ChangeType t = event.getChangeType();
        return t == ChangeType.CREATE || t == ChangeType.UPDATE || t == ChangeType.DELETE;
    }

    @Override
    public void process(ResourceEvent event) {
        CloudResource resource = event.getPayload();

        String assetUri = firstNonBlank(resource.getUri(), resource.getResourceId());
        if (assetUri == null) {
            // Without a stable identity the catalog upsert would drop the event as
            // "missing assetUri"; skip rather than emit an un-actionable record.
            // Log only stable identifiers - not the whole event (avoids leaking
            // account/tag metadata into logs, CLAUDE.md §11).
            log.warn("Crawler resource has neither uri nor resourceId; not bridging to spine. eventId={} provider={}",
                    event.getEventId(), event.getCloudProvider());
            return;
        }

        // The canonical id (ADR-0047) is the join key with the catalog. Crawlers store it as the
        // resource uri; a resource that arrives without one (an older crawler build, a legacy
        // event) is resolved here from its uri or resourceId with hints from the resource itself.
        Optional<CanonicalAssetId> canonical = CrawlerAssetIds.resolve(resource);
        String nativeKey = nativeKey(event, assetUri);

        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", computedEventId(event, resource, nativeKey)
                .orElseGet(() -> firstNonBlank(event.getEventId(),
                        assetUri + ":" + event.getChangeType() + ":" + tsString(event))));
        node.put("nativeEventId", nativeKey);
        node.put("changeType", event.getChangeType().name());
        node.put("source", SOURCE_CRAWLER);
        node.put("operation", OPERATION_DISCOVERY);
        putIfPresent(node, "sourceProvider",
                firstNonBlank(event.getCloudProvider(), resource.getCloudProvider()));
        putIfPresent(node, "eventTime", tsString(event));

        // targetResource carries asset identity in the shape da-catalog's listener
        // (toAssetDescriptor) reads: id -> assetUri, name, kind -> serviceType, region.
        ObjectNode target = node.putObject("targetResource");
        target.put("id", assetUri);
        putIfPresent(target, "name", resource.getName());
        // The kind is the DLAP service type when the resource is an asset kind (log-event.md 4.2),
        // else the crawler's own type id, as before.
        putIfPresent(target, "kind", canonical.map(c -> c.kind().serviceType()).orElse(resource.getTypeId()));
        putIfPresent(target, "region", resource.getRegion());
        canonical.ifPresent(c -> target.put("canonicalUri", c.uri()));

        // Provider creation time and observed size (ADR-0046). A creation time the crawler did not
        // read and a size it did not observe stay absent; absent is unknown, never zero.
        Instant created = resource.getCreatedAt();
        if (created != null) {
            target.put("createdTime", created.toString());
        }
        // proto3 JSON writes int64 as a string (log-event.md section 4.2).
        CrawlerAssetIds.observedSizeBytes(resource).ifPresent(size -> target.put("sizeBytes", Long.toString(size)));

        // Forward the crawler's technical metadata (storageClass/size/versioning/isPublic/
        // encryption/...) so da-catalog persists it into dlp_assets.technical_metadata. This
        // is the fuel for /assets/{id}/schema and for the IAM-drift / PII-encryption compliance
        // evaluators - cloud-neutral, benefits every provider whose crawler captures it.
        Map<String, String> tech = resource.getTechnicalMetadata();
        if (tech != null && !tech.isEmpty()) {
            target.set("technicalMetadata", objectMapper.valueToTree(tech));
        }

        // Print the event once, in the single wire encoding (log-event.md section 2): the codec
        // reads the flat tree built above, maps the "source" marker onto origin and source_format,
        // and prints canonical proto3 JSON. The String goes out on a String-valued template, so the
        // record value is the JSON object itself, not a JSON string holding it (the legacy double
        // encoding every producer used before SPINE-01).
        String changeType = event.getChangeType().name();
        String json = SpineEvents.encode(node);

        // Partition by asset identity so all events for one asset stay causally ordered. The key is
        // the crawler's identifier, which is the canonical URI for every crawler that stores one.
        // Keys of other writers change to the canonical URI only after every writer emits
        // canonical ids (ADR-0047 item 8, the ID-03 runbook); the router already keys the typed
        // topics by the canonical URI.
        //
        // Reliability: the send is awaited (SpineEvents.sendAndAwait), so the failure of a lost
        // event is raised here, before the caller acknowledges the crawler event, and not logged
        // from a callback afterwards. The delivery guarantee is at-least-once: an idempotent
        // producer (KafkaConfiguration) plus a deterministic event id and idempotent downstream
        // upserts (catalog upsert is keyed by asset URI; lineage by a unique edge index) make
        // duplicates benign.
        SpineEvents.sendAndAwait(kafkaTemplate, assetUri, json, publishTimeout);
        log.debug("Bridged {} crawler event for {} onto the log-event spine", changeType, assetUri);

        // Central discovery publish point: count assets bridged to the catalog spine.
        // Low cardinality - provider and serviceType (typeId) are bounded; never assetId.
        String provider = firstNonBlank(event.getCloudProvider(), resource.getCloudProvider(), TAG_UNKNOWN);
        String serviceType = firstNonBlank(resource.getTypeId(), TAG_UNKNOWN);
        meterRegistry.counter(DISCOVERY_ASSETS_COUNTER, "provider", provider, "serviceType", serviceType)
                .increment();
        if (canonical.isEmpty()) {
            meterRegistry.counter(DISCOVERY_UNRESOLVED_COUNTER, "provider", provider, "serviceType", serviceType)
                    .increment();
        }
    }

    @Override
    public int getPriority() {
        // After CatalogResourceEventProcessor (10): persist the inventory first,
        // then announce the asset to the catalog spine.
        return 20;
    }

    @Override
    public String getName() {
        return "CrawlerAssetSpineBridge";
    }

    /**
     * The native key of the event (log-event.md section 5.2): {@code DLAP_CRAWLER|} plus the crawler
     * event's own id, else the asset, the change and the crawl time.
     */
    private static String nativeKey(ResourceEvent event, String assetUri) {
        if (event.getEventId() != null && !event.getEventId().isBlank()) {
            return LogEventIds.namespacedNativeKey(SOURCE_KIND, event.getEventId());
        }
        String ts = tsString(event);
        return LogEventIds.namespacedNativeKey(SOURCE_KIND, assetUri, event.getChangeType().name(),
                ts == null ? NONE : ts);
    }

    /**
     * {@code event_id = hex(SHA-256("<SOURCE_PROVIDER>|<native key>"))}, or empty when the crawler
     * event names no known provider (the id the crawler sent is used then).
     */
    private static Optional<String> computedEventId(ResourceEvent event, CloudResource resource, String nativeKey) {
        CloudProvider provider = CloudProvider.fromName(
                firstNonBlank(event.getCloudProvider(), resource.getCloudProvider()));
        return provider == CloudProvider.UNKNOWN
                ? Optional.empty()
                : Optional.of(LogEventIds.eventId(provider.name(), nativeKey));
    }

    private static String tsString(ResourceEvent event) {
        return event.getEventTimestamp() == null ? null : event.getEventTimestamp().toString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
