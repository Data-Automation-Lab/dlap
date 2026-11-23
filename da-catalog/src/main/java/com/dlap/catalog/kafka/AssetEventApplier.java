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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.catalog.idempotency.ProcessedEventStore;
import com.dlap.catalog.kafka.AssetStateRules.LifecycleOutcome;
import com.dlap.catalog.kafka.AssetStateRules.Transition;
import com.dlap.catalog.lifecycle.AppliedLogEvent;
import com.dlap.catalog.service.DiscoveryAssetUpsertService;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ResultStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/**
 * Applies one lifecycle-topic event to the catalog under the event-time rules (ADR-0046,
 * docs/specs/asset-descriptor.md "Catalog event ingest"). Runs inside the listener's
 * transaction; every change it makes commits or rolls back with its processed-event claims.
 *
 * <ol>
 *   <li>Gate: the event must have a business time and a known change type
 *       ({@code LogEventSemantics.mayChangeState}).</li>
 *   <li>Targets: the canonical target, unless it is an object outside every governed prefix;
 *       and, for an object, the container it rolls up to. A CREATE from a crawler or a
 *       management event may create the direct row; nothing else creates rows.</li>
 *   <li>Unknown targets: when no row matches, nothing changes, the event is counted in
 *       {@code dlap_catalog_unmatched_target_total} and no claim is recorded, so a replay after
 *       the row appears applies it.</li>
 *   <li>For each row, in a fixed order (object, then container): lock it, claim
 *       {@code (catalog-asset-state, event key, asset)}, and only if the claim is new apply
 *       the rules and count usage under a second claim on the usage dedup key.</li>
 *   <li>For each row, new claim or not, publish an {@link AppliedLogEvent} in the same transaction,
 *       so the Lifecycle State evidence (LSE-01) names the events behind the stored values; a
 *       redelivery or a replay of an applied event backfills that evidence.</li>
 * </ol>
 */
@Component
public class AssetEventApplier {

    private static final Logger log = LoggerFactory.getLogger(AssetEventApplier.class);

    /** Key in the deprecated {@code dlap_usage_metrics} bag, mirrored for legacy readers until CAT-02b. */
    static final String LEGACY_ACCESS_COUNT = "accessCount";
    /** Key in the deprecated {@code dlap_usage_metrics} bag; now mirrors the event-time {@code lastAccessedAt}. */
    static final String LEGACY_LAST_ACCESSED_AT = "lastAccessedAt";

    /** What the applier did with an event. */
    public enum Outcome {
        /** At least one row changed. */
        APPLIED,
        /** Every matched row had already applied the event. */
        DUPLICATE,
        /** No row matched the (rolled-up) target. */
        UNMATCHED,
        /**
         * The event cannot change state (no business time, unknown change type, no target), or
         * it is a crawler observation of an object outside every governed prefix.
         */
        SKIPPED
    }

    private final AssetMatcher matcher;
    private final ProcessedEventStore processed;
    private final AssetUsageDailyStore usage;
    private final DiscoveryAssetUpsertService upsertService;
    private final CatalogIngestProperties properties;
    private final CatalogIngestMetrics metrics;
    private final ApplicationEventPublisher events;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates the applier.
     *
     * @param matcher       finds rows for targets
     * @param processed     the processed-event claims
     * @param usage         the daily usage counters
     * @param upsertService creates and refreshes rows
     * @param properties    governed prefixes
     * @param metrics       the consumer metrics
     * @param events        publishes {@link AppliedLogEvent} for the Lifecycle State evidence
     */
    public AssetEventApplier(AssetMatcher matcher, ProcessedEventStore processed, AssetUsageDailyStore usage,
                             DiscoveryAssetUpsertService upsertService, CatalogIngestProperties properties,
                             CatalogIngestMetrics metrics, ApplicationEventPublisher events) {
        this.matcher = matcher;
        this.processed = processed;
        this.usage = usage;
        this.upsertService = upsertService;
        this.properties = properties;
        this.metrics = metrics;
        this.events = events;
    }

    /**
     * Waits for the normalized-URI index outside any transaction ({@link AssetMatcher#awaitIndex}).
     * The listener calls it before {@link #apply}.
     */
    public void awaitMatchIndex() {
        matcher.awaitIndex();
    }

    /**
     * Applies an event.
     *
     * @param event            the decoded event
     * @param publishDiscovery whether to publish {@code ASSET_CREATED} and {@code ASSET_UPDATED}
     *                         after commit (the discovered topic)
     * @return what happened
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome apply(CatalogEvent event, boolean publishDiscovery) {
        String consumer = ProcessedEventStore.CONSUMER_ASSET_STATE;
        if (!event.mayChangeState() || event.target() == null) {
            metrics.outcome(consumer, "skipped");
            return Outcome.SKIPPED;
        }
        Instant eventTime = event.eventTime().orElseThrow();

        Optional<CanonicalAssetId> canonical = event.canonicalTarget();
        Target direct = null;
        Target parent = null;
        if (canonical.isPresent()) {
            CanonicalAssetId id = canonical.get();
            boolean object = id.isObjectLevel();
            if (object && event.isCrawler() && !properties.isGoverned(id)) {
                // A crawl of an ungoverned object is neither a row nor activity of its container.
                metrics.outcome(consumer, "skipped");
                return Outcome.SKIPPED;
            }
            if (!object || properties.isGoverned(id)) {
                direct = directTarget(event, id, eventTime);
            }
            if (object && !id.rollUp().equals(id) && !event.isCrawler()) {
                parent = matcher.find(id.rollUp()).map(m -> new Target(m.assetId(), false)).orElse(null);
            }
        } else {
            AssetIdParseResult.Rejected rejected = (AssetIdParseResult.Rejected) event.target();
            metrics.unresolved(event.providerTag(), rejected.reason().name());
            String provider = CatalogEvent.UNKNOWN_PROVIDER.equals(event.providerTag()) ? null : event.providerTag();
            direct = matcher.findRaw(event.rawTargetId(), provider).map(m -> new Target(m.assetId(), false))
                    .orElse(null);
        }

        if (direct == null && parent == null) {
            String kind = canonical.map(id -> id.rollUp().kind().name()).orElse("UNRESOLVED");
            String provider = canonical.map(CanonicalAssetId::cloudProvider).orElse(event.providerTag());
            metrics.unmatched(provider, kind, event.categoryTag());
            metrics.outcome(consumer, "unmatched");
            log.debug("No catalog row for event {} ({}); counted as unmatched", event.eventKey(), kind);
            return Outcome.UNMATCHED;
        }

        boolean changed = false;
        if (direct != null) {
            changed |= applyToRow(event, eventTime, direct, true, publishDiscovery);
        }
        if (parent != null) {
            changed |= applyToRow(event, eventTime, parent, false, false);
        }
        metrics.outcome(consumer, changed ? "applied" : "duplicate");
        return changed ? Outcome.APPLIED : Outcome.DUPLICATE;
    }

    /** The direct row: found, or created by a CREATE that may create rows. */
    private Target directTarget(CatalogEvent event, CanonicalAssetId id, Instant eventTime) {
        Optional<AssetMatcher.Match> match = matcher.find(id);
        if (match.isPresent()) {
            return new Target(match.get().assetId(), false);
        }
        if (!mayCreate(event, id)) {
            return null;
        }
        Asset created = upsertService.create(event, id, eventTime);
        return new Target(created.getAssetId(), true);
    }

    /**
     * Rows come from crawlers and from successful management CREATE events (ADR-0046
     * section 2); under a governed prefix an object's creating write also creates its row.
     * Reads, updates, deletes and permission changes never create rows.
     *
     * @param event the event
     * @param id    the direct target (container level, or a governed object)
     * @return whether the event may create the row
     */
    static boolean mayCreate(CatalogEvent event, CanonicalAssetId id) {
        if (event.changeType() != ChangeType.CREATE || !event.isProven()) {
            return false;
        }
        EventCategory category = event.category();
        return event.isCrawler()
                || category == EventCategory.EVENT_CATEGORY_MANAGEMENT
                || (id.isObjectLevel() && category == EventCategory.EVENT_CATEGORY_DATA_WRITE);
    }

    private boolean applyToRow(CatalogEvent event, Instant eventTime, Target target, boolean direct,
                               boolean publishDiscovery) {
        Asset asset = entityManager.find(Asset.class, target.assetId(), LockModeType.PESSIMISTIC_WRITE);
        if (asset == null) {
            log.debug("Catalog row {} disappeared before event {} applied", target.assetId(), event.eventKey());
            return false;
        }
        String consumer = ProcessedEventStore.CONSUMER_ASSET_STATE;
        if (!processed.claim(consumer, event.eventKey(), asset.getAssetId(), eventTime)) {
            publishApplied(asset, event, eventTime, direct);
            return false;
        }
        event.supersedes().ifPresent(old -> processed.claim(consumer, old, asset.getAssetId(), eventTime));

        if (direct) {
            applyDirect(asset, event, eventTime, target.created());
        } else {
            applyRolledUp(asset, event, eventTime);
        }
        countUsage(asset, event, eventTime, direct);
        publishApplied(asset, event, eventTime, direct);

        if (publishDiscovery && direct) {
            upsertService.publishAfterCommit(asset, target.created()
                    ? DiscoveryAssetUpsertService.ASSET_CREATED : DiscoveryAssetUpsertService.ASSET_UPDATED);
        }
        return true;
    }

    /**
     * Tells the Lifecycle State evidence recorder (LSE-01) that the event was applied to the row, or
     * had been: the recorder names the event only when the value it offers equals the stored one.
     */
    private void publishApplied(Asset asset, CatalogEvent event, Instant eventTime, boolean direct) {
        EventCategory category = direct ? event.category() : rolledUpCategory(event);
        events.publishEvent(new AppliedLogEvent(asset, event, eventTime, category, direct));
    }

    /** The event acts on this row itself: lifecycle, creation time, size, activity, metadata. */
    private void applyDirect(Asset asset, CatalogEvent event, Instant eventTime, boolean created) {
        Optional<Transition> transition = transitionOf(event);
        if (transition.isPresent() && !created) {
            LifecycleOutcome outcome = AssetStateRules.applyLifecycle(asset, transition.get(), eventTime,
                    event.channel());
            if (outcome == LifecycleOutcome.NEW_INCARNATION) {
                resetLegacyUsageMirror(asset);
                log.info("Asset {} recreated at {} (new incarnation)", asset.getAssetUri(), asset.getRecreatedAt());
            }
        }
        if (event.isProven()) {
            event.createdTime().ifPresent(c -> AssetStateRules.applyCreatedTime(asset, c, event.channel()));
            if (!event.isCrawler() && transition.filter(t -> t == Transition.CREATE).isPresent()) {
                AssetStateRules.applyCreatedTime(asset, eventTime, event.channel());
            }
            event.sizeBytes().ifPresent(size -> AssetStateRules.applySize(asset, size, eventTime));
        }
        if (isSuccess(event) && AssetStateRules.applyActivity(asset, event.category(), eventTime, event.channel())) {
            mirrorLastAccess(asset);
        }
        ChangeType type = event.changeType();
        if (event.isCrawler() || type == ChangeType.CREATE || type == ChangeType.UPDATE) {
            upsertService.refresh(asset, event);
        }
        if (type != ChangeType.ACCESS && type != ChangeType.PERMISSION) {
            upsertService.markScanned(asset);
        }
    }

    /** The event acts on an object of this container: only the activity maxima move. */
    private void applyRolledUp(Asset parent, CatalogEvent event, Instant eventTime) {
        EventCategory category = rolledUpCategory(event);
        if (isSuccess(event) && AssetStateRules.applyActivity(parent, category, eventTime, event.channel())) {
            mirrorLastAccess(parent);
        }
    }

    /**
     * Usage counts: a successful recorded read adds a read, a successful data write adds a
     * write, each once per usage dedup key and asset. {@code asset_usage_daily} is a history
     * by day and counts a late read of an earlier incarnation on its own day, whatever the
     * delivery order; the legacy {@code accessCount} counts the current incarnation only (a new
     * incarnation resets it), so a read older than {@code recreatedAt} does not add to it.
     */
    private void countUsage(Asset asset, CatalogEvent event, Instant eventTime, boolean direct) {
        EventCategory category = direct ? event.category() : rolledUpCategory(event);
        boolean read = LogEventSemantics.countsAsUsage(event.event());
        boolean write = !read && isSuccess(event) && category == EventCategory.EVENT_CATEGORY_DATA_WRITE;
        if (!read && !write) {
            return;
        }
        if (!processed.claim(ProcessedEventStore.CONSUMER_USAGE, event.usageKey(), asset.getAssetId(), eventTime)) {
            return;
        }
        if (read) {
            usage.addRead(asset.getAssetId(), eventTime);
            if (AssetStateRules.withinIncarnation(asset, eventTime)) {
                Map<String, Object> bag = legacyBag(asset);
                Object count = bag.getOrDefault(LEGACY_ACCESS_COUNT, 0L);
                bag.put(LEGACY_ACCESS_COUNT, (count instanceof Number n ? n.longValue() : 0L) + 1);
                asset.setDlapUsageMetrics(bag);
            }
        } else {
            usage.addWrite(asset.getAssetId(), eventTime);
        }
    }

    /**
     * The status transition a direct event carries: a successful management CREATE, DELETE or
     * ARCHIVE (crawler observations count as management).
     */
    static Optional<Transition> transitionOf(CatalogEvent event) {
        if (!event.isProven() || event.category() != EventCategory.EVENT_CATEGORY_MANAGEMENT) {
            return Optional.empty();
        }
        return switch (event.changeType()) {
            case CREATE -> Optional.of(Transition.CREATE);
            case DELETE -> Optional.of(Transition.DELETE);
            case ARCHIVE -> Optional.of(Transition.ARCHIVE);
            default -> Optional.empty();
        };
    }

    /**
     * The category an object event has for its container. Explicit data and permission
     * categories carry over. A legacy event has no category; its CREATE, UPDATE or DELETE of
     * an object is a write of the container's content, not a change of the container itself.
     */
    static EventCategory rolledUpCategory(CatalogEvent event) {
        EventCategory category = event.category();
        boolean legacyManagement = category == EventCategory.EVENT_CATEGORY_MANAGEMENT
                && event.event().getEventCategory() == EventCategory.EVENT_CATEGORY_UNSPECIFIED;
        if (legacyManagement) {
            ChangeType type = event.changeType();
            if (type == ChangeType.CREATE || type == ChangeType.UPDATE || type == ChangeType.DELETE) {
                return EventCategory.EVENT_CATEGORY_DATA_WRITE;
            }
            return EventCategory.EVENT_CATEGORY_UNSPECIFIED;
        }
        return category == EventCategory.EVENT_CATEGORY_MANAGEMENT ? EventCategory.EVENT_CATEGORY_UNSPECIFIED : category;
    }

    private static boolean isSuccess(CatalogEvent event) {
        return event.event().getResultStatus() == ResultStatus.SUCCESS;
    }

    private static void mirrorLastAccess(Asset asset) {
        if (asset.getLastAccessedAt() == null) {
            return;
        }
        Map<String, Object> bag = legacyBag(asset);
        bag.put(LEGACY_LAST_ACCESSED_AT, asset.getLastAccessedAt().toString());
        asset.setDlapUsageMetrics(bag);
    }

    private static void resetLegacyUsageMirror(Asset asset) {
        Map<String, Object> bag = legacyBag(asset);
        bag.put(LEGACY_ACCESS_COUNT, 0L);
        if (asset.getLastAccessedAt() == null) {
            bag.remove(LEGACY_LAST_ACCESSED_AT);
        } else {
            bag.put(LEGACY_LAST_ACCESSED_AT, asset.getLastAccessedAt().toString());
        }
        asset.setDlapUsageMetrics(bag);
    }

    private static Map<String, Object> legacyBag(Asset asset) {
        return asset.getDlapUsageMetrics() == null ? new HashMap<>() : new HashMap<>(asset.getDlapUsageMetrics());
    }

    /** A row the event applies to, and whether this event created it. */
    private record Target(UUID assetId, boolean created) {
    }
}
