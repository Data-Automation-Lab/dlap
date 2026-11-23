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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;

/**
 * One decoded log event as the catalog consumers see it: the {@link DlapLogEvent} read
 * through {@code DlapLogEventCodec} plus the facts the catalog derives once (dedup keys,
 * business time, evidence channel, the normalized target). Built by
 * {@link CatalogEventDecoder}; immutable.
 */
public final class CatalogEvent {

    /** Evidence channel of crawler observations (asset-descriptor.md, evidence channels). */
    public static final String CHANNEL_CRAWLER = "CRAWLER";
    /** Evidence channel of provider audit-log records. */
    public static final String CHANNEL_AUDIT_LOG = "AUDIT_LOG";
    /** Evidence channel of provider-maintained state, such as an observed last-access time. */
    public static final String CHANNEL_PROVIDER_METADATA = "PROVIDER_METADATA";
    /** Metric tag of an event whose source provider is unspecified or unrecognized. */
    public static final String UNKNOWN_PROVIDER = "UNKNOWN";

    private final DlapLogEvent event;
    private final boolean legacy;
    private final String eventKey;
    private final String usageKey;
    private final String channel;
    private final AssetIdParseResult target;

    CatalogEvent(DlapLogEvent event, boolean legacy, String eventKey, String usageKey, String channel,
                 AssetIdParseResult target) {
        this.event = Objects.requireNonNull(event, "event");
        this.legacy = legacy;
        this.eventKey = Objects.requireNonNull(eventKey, "eventKey");
        this.usageKey = Objects.requireNonNull(usageKey, "usageKey");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.target = target;
    }

    /**
     * The decoded event.
     *
     * @return the event
     */
    public DlapLogEvent event() {
        return event;
    }

    /**
     * Whether the payload used a legacy form (double encoding, legacy keys).
     *
     * @return {@code true} for a legacy payload
     */
    public boolean isLegacy() {
        return legacy;
    }

    /**
     * The processed-event key: {@code event_id}, or {@code payload:<sha256>} of the event when
     * the producer sent none (a redelivered record has the same bytes, so the same key).
     *
     * @return the key, never blank
     */
    public String eventKey() {
        return eventKey;
    }

    /**
     * The usage dedup key ({@code LogEventIds.usageDedupKey}): the shared key across accounts
     * when CloudTrail {@code sharedEventID} is set, else the event key. An event that
     * supersedes another uses the superseded event's key, so a correction never counts twice.
     *
     * @return the key, never blank
     */
    public String usageKey() {
        return usageKey;
    }

    /**
     * The {@code event_id} this event replaces, if any.
     *
     * @return the superseded id, or empty
     */
    public Optional<String> supersedes() {
        String s = event.getSupersedesEventId();
        return s.isBlank() ? Optional.empty() : Optional.of(s);
    }

    /**
     * The routing key.
     *
     * @return the change type
     */
    public ChangeType changeType() {
        return event.getChangeType();
    }

    /**
     * The category the catalog acts on ({@code LogEventSemantics.effectiveCategory}).
     *
     * @return the category; legacy events derive it from the change type
     */
    public EventCategory category() {
        return LogEventSemantics.effectiveCategory(event);
    }

    /**
     * Whether the event may change state: business time present, known change type, not a
     * minimal pass-through ({@code LogEventSemantics.mayChangeState}).
     *
     * @return the gate
     */
    public boolean mayChangeState() {
        return LogEventSemantics.mayChangeState(event);
    }

    /**
     * Whether the event comes from a da-discovery crawler (an observation of provider state).
     *
     * @return {@code true} for {@code ORIGIN_CRAWLER}
     */
    public boolean isCrawler() {
        return event.getOrigin() == Origin.ORIGIN_CRAWLER;
    }

    /**
     * Whether the event proves its fact: {@code SUCCESS}, or a crawler observation, which the
     * legacy bridge sends without a result status (it observed the resource, so nothing
     * failed). {@code FAILURE} and {@code PENDING} never prove anything.
     *
     * @return {@code true} when the event may move business state
     */
    public boolean isProven() {
        ResultStatus status = event.getResultStatus();
        if (status == ResultStatus.SUCCESS) {
            return true;
        }
        return isCrawler() && (status == ResultStatus.RESULT_STATUS_UNSPECIFIED || status == ResultStatus.UNRECOGNIZED);
    }

    /**
     * The business time ({@code event_time}).
     *
     * @return the event time, or empty
     */
    public Optional<Instant> eventTime() {
        return LogEventSemantics.businessTime(event);
    }

    /**
     * The provider creation time the target carries ({@code targetResource.created_time}).
     *
     * @return the creation time, or empty
     */
    public Optional<Instant> createdTime() {
        TargetResource t = event.getTargetResource();
        if (!t.hasCreatedTime()) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(t.getCreatedTime().getSeconds(), t.getCreatedTime().getNanos()));
    }

    /**
     * The observed size ({@code targetResource.size_bytes}, explicit presence).
     *
     * @return the size, or empty when unknown
     */
    public Optional<Long> sizeBytes() {
        TargetResource t = event.getTargetResource();
        return t.hasSizeBytes() && t.getSizeBytes() >= 0 ? Optional.of(t.getSizeBytes()) : Optional.empty();
    }

    /**
     * The evidence channel for fields this event sets: {@code CRAWLER}, or
     * {@code PROVIDER_METADATA[:<SOURCE_KIND>]} for an observed read, else
     * {@code AUDIT_LOG[:<SOURCE_KIND>]}.
     *
     * @return the channel
     */
    public String channel() {
        return channel;
    }

    /**
     * The target as normalized ({@code canonical_uri}, else {@code id}, with hints).
     *
     * @return the normalization result, or {@code null} when the event names no target
     */
    public AssetIdParseResult target() {
        return target;
    }

    /**
     * The canonical target, when it normalized.
     *
     * @return the canonical ID, or empty
     */
    public Optional<CanonicalAssetId> canonicalTarget() {
        return target == null ? Optional.empty() : target.id();
    }

    /**
     * The target as the producer named it: {@code targetResource.id}, else {@code canonical_uri}.
     *
     * @return the raw identifier, or an empty string
     */
    public String rawTargetId() {
        TargetResource t = event.getTargetResource();
        return !t.getId().isBlank() ? t.getId() : t.getCanonicalUri();
    }

    /**
     * The target's display name.
     *
     * @return the name, or empty
     */
    public Optional<String> targetName() {
        String n = event.getTargetResource().getName();
        return n.isBlank() ? Optional.empty() : Optional.of(n);
    }

    /**
     * The target's region.
     *
     * @return the region, or empty
     */
    public Optional<String> targetRegion() {
        String r = event.getTargetResource().getRegion();
        return r.isBlank() ? Optional.empty() : Optional.of(r);
    }

    /**
     * The target's observed technical metadata.
     *
     * @return the map, possibly empty
     */
    public Map<String, String> technicalMetadata() {
        return event.getTargetResource().getTechnicalMetadataMap();
    }

    /**
     * The source provider name for metric tags.
     *
     * @return the provider enum name, or {@code UNKNOWN}
     */
    public String providerTag() {
        SourceProvider p = event.getSourceProvider();
        return p == SourceProvider.SOURCE_PROVIDER_UNSPECIFIED || p == SourceProvider.UNRECOGNIZED ? UNKNOWN_PROVIDER : p.name();
    }

    /**
     * The category name without its enum prefix, for metric tags.
     *
     * @return for example {@code DATA_READ}, or {@code UNSPECIFIED}
     */
    public String categoryTag() {
        EventCategory c = category();
        if (c == EventCategory.UNRECOGNIZED) {
            return "UNSPECIFIED";
        }
        return c.name().replace("EVENT_CATEGORY_", "");
    }
}
