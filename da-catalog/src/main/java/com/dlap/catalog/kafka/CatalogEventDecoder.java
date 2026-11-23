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

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.logevent.DecodedLogEvent;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventDecodeException;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.common.logevent.SourceFormats;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;

/**
 * Reads every catalog payload through {@link DlapLogEventCodec} (ADR-0045 dual read: the
 * single encoding, the legacy double encoding and the pre-ADR-0045 producer shapes) and
 * normalizes the target with {@link AssetIdNormalizer} (ADR-0047). The one place the
 * catalog derives dedup keys, the evidence channel and normalization hints.
 */
@Component
public class CatalogEventDecoder {

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final CatalogIngestProperties properties;
    private final CatalogIngestMetrics metrics;

    /**
     * Creates the decoder.
     *
     * @param properties the ingest settings (default normalization hints)
     * @param metrics    the consumer metrics
     */
    public CatalogEventDecoder(CatalogIngestProperties properties, CatalogIngestMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Decodes a payload.
     *
     * @param payload  the record value (a String in every catalog consumer)
     * @param consumer the processed-event consumer name, for the decode counter
     * @return the catalog view of the event
     * @throws LogEventDecodeException when the payload is not a log event; the listener lets
     *                                 it propagate so the error handler dead-letters the record
     */
    public CatalogEvent decode(Object payload, String consumer) {
        DecodedLogEvent decoded = codec.decodeDetailed(payload);
        metrics.decoded(consumer, decoded.isLegacy());
        DlapLogEvent event = decoded.event();
        String eventKey = event.getEventId().isBlank()
                ? "payload:" + LogEventIds.sha256Hex(codec.encode(event))
                : event.getEventId();
        return new CatalogEvent(event, decoded.isLegacy(), eventKey, usageKey(event, eventKey), channel(event),
                resolveTarget(event));
    }

    /**
     * The event as the lineage extractor and metadata harvester expect it: the wire JSON map
     * of the decoded event ({@code DlapLogEventCodec.toMap}).
     *
     * @param event a decoded event
     * @return a mutable lowerCamelCase map
     */
    public java.util.Map<String, Object> toMap(DlapLogEvent event) {
        return codec.toMap(event);
    }

    /**
     * Normalizes an identifier stored in {@code dlap_assets.asset_uri}, with hints from the row
     * and the configured defaults: {@code normalize(existing asset_uri)}.
     *
     * @param assetUri      the stored URI
     * @param serviceType   the row's service type (kind hint)
     * @param cloudProvider the row's provider (provider hint)
     * @param region        the row's region
     * @return the normalization result
     */
    public AssetIdParseResult normalizeStored(String assetUri, String serviceType, String cloudProvider,
                                              String region) {
        AssetIdHints.Builder b = AssetIdHints.builder();
        AssetProvider.fromString(cloudProvider).ifPresent(b::provider);
        AssetKind.fromServiceType(serviceType).ifPresent(b::kind);
        b.region(region);
        return AssetIdNormalizer.parse(assetUri, withDefaults(b));
    }

    /**
     * Normalizes an identifier referenced by an event (a lineage endpoint, for example), with
     * the event's provider as the only event hint.
     *
     * @param raw      the identifier
     * @param provider the event's source provider
     * @return the normalization result
     */
    public AssetIdParseResult normalizeReference(String raw, SourceProvider provider) {
        AssetIdHints.Builder b = AssetIdHints.builder();
        assetProvider(provider).ifPresent(b::provider);
        return AssetIdNormalizer.parse(raw, withDefaults(b));
    }

    /**
     * The target normalized from {@code canonical_uri} (preferred, re-validated) or else
     * {@code id}, with hints from the event: provider, kind, project and region.
     *
     * @param event the event
     * @return the result, or {@code null} when the event names no target
     */
    AssetIdParseResult resolveTarget(DlapLogEvent event) {
        TargetResource t = event.getTargetResource();
        AssetIdHints.Builder b = AssetIdHints.builder();
        assetProvider(event.getSourceProvider()).ifPresent(b::provider);
        TargetKindHints.fromTargetKind(t.getKind()).ifPresent(b::kind);
        b.project(t.getProject()).region(t.getRegion());
        AssetIdHints hints = withDefaults(b);

        AssetIdParseResult fromCanonical = null;
        if (!t.getCanonicalUri().isBlank()) {
            fromCanonical = AssetIdNormalizer.parse(t.getCanonicalUri(), hints);
            if (fromCanonical.isNormalized()) {
                return fromCanonical;
            }
        }
        if (!t.getId().isBlank()) {
            return AssetIdNormalizer.parse(t.getId(), hints);
        }
        return fromCanonical;
    }

    private AssetIdHints withDefaults(AssetIdHints.Builder builder) {
        AssetIdHints current = builder.build();
        return properties.getMatch().getDefaultHints().applyTo(current.toBuilder(), current).build();
    }

    private static Optional<AssetProvider> assetProvider(SourceProvider provider) {
        if (provider == null || provider == SourceProvider.SOURCE_PROVIDER_UNSPECIFIED
                || provider == SourceProvider.UNRECOGNIZED) {
            return Optional.empty();
        }
        return AssetProvider.fromString(provider.name());
    }

    /**
     * Usage dedup key: {@code LogEventIds.usageDedupKey} when {@code shared_event_id} is set;
     * otherwise the superseded event's id when this event replaces one, else the event key.
     */
    private static String usageKey(DlapLogEvent event, String eventKey) {
        if (!event.getSharedEventId().isBlank()) {
            return LogEventIds.usageDedupKey(event);
        }
        if (!event.getSupersedesEventId().isBlank()) {
            return event.getSupersedesEventId();
        }
        return eventKey;
    }

    private static String channel(DlapLogEvent event) {
        if (event.getOrigin() == com.dlap.proto.log.v1.Origin.ORIGIN_CRAWLER) {
            return CatalogEvent.CHANNEL_CRAWLER;
        }
        String base = event.getEventCategory() == EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED
                ? CatalogEvent.CHANNEL_PROVIDER_METADATA
                : CatalogEvent.CHANNEL_AUDIT_LOG;
        return SourceFormats.sourceKind(event.getSourceFormat()).map(kind -> base + ":" + kind).orElse(base);
    }
}
