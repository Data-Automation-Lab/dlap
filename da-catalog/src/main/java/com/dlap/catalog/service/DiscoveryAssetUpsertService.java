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

package com.dlap.catalog.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogEvent;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.model.entity.Asset;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Creates and refreshes catalog rows from discovery evidence (crawler observations and
 * CREATE events), for {@code AssetEventApplier}.
 *
 * <p>Identity, provider and type come only from the canonical ID (ADR-0047): the row's
 * {@code asset_uri} is the canonical URI, {@code cloud_provider} and {@code service_type}
 * are the ID's, and there is no default provider. The raw form the producer sent is kept in
 * {@code native_asset_id}, the first native form seen. Business times are not set here; the
 * applier applies them under the event-time rules.
 *
 * <p>Every method runs inside the applier's transaction, after its processed-event claim.
 * Asset-change notifications are published only after that transaction commits, so a rolled
 * back event never announces a change.
 */
@Service
public class DiscoveryAssetUpsertService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryAssetUpsertService.class);

    /**
     * {@code AssetChangeEvent} type of a row the catalog created. The event types are not yet
     * {@code DlapConstants} entries (da-protos); until they are, the catalog consumers name
     * them here.
     */
    public static final String ASSET_CREATED = "ASSET_CREATED";
    /** {@code AssetChangeEvent} type of a row the catalog changed. */
    public static final String ASSET_UPDATED = "ASSET_UPDATED";

    /** Widths of the columns the service fills from event text (dlap_assets). */
    static final int MAX_NATIVE_ID = 500;
    static final int MAX_NAME = 255;
    static final int MAX_REGION = 50;

    private final AssetService assetService;
    private final CanonicalUriIndex canonicalUriIndex;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates the service.
     *
     * @param assetService      publishes asset-change events
     * @param canonicalUriIndex records the canonical form of created rows
     * @param clock             the source of the operational scan times
     */
    public DiscoveryAssetUpsertService(AssetService assetService, CanonicalUriIndex canonicalUriIndex, Clock clock) {
        this.assetService = assetService;
        this.canonicalUriIndex = canonicalUriIndex;
        this.clock = clock;
    }

    /**
     * Test seam: the entity manager outside a Spring context.
     *
     * @param entityManager the entity manager
     */
    void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Creates the row for a canonical ID from a CREATE event and flushes it.
     *
     * @param event     the event
     * @param id        the canonical ID of the new row
     * @param eventTime the event time: the first-seen time, and the lifecycle time unless the
     *                  event is a crawler observation (see {@link #initialLifecycleTime})
     * @return the persisted row, status {@code ACTIVE}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Asset create(CatalogEvent event, CanonicalAssetId id, Instant eventTime) {
        Objects.requireNonNull(eventTime, "eventTime");
        Asset created = new Asset(id.uri(), id.cloudProvider(), id.serviceType(), nameFor(event, id));
        String raw = event.rawTargetId();
        if (!raw.isBlank() && raw.length() <= MAX_NATIVE_ID) {
            created.setNativeAssetId(raw);
        }
        String region = event.targetRegion().orElse(id.attribute(CanonicalAssetId.Attribute.REGION).orElse(null));
        if (region != null && region.length() <= MAX_REGION) {
            created.setRegion(region);
        }
        created.setStatus(DlapConstants.AssetStatus.ACTIVE);
        created.setLifecycleEventTime(initialLifecycleTime(event, eventTime));
        created.setDiscoveredAt(eventTime);
        created.setDlapDiscoveredTs(eventTime);
        created.setDiscoverySource(event.channel().length() <= 64 ? event.channel() : event.channel().substring(0, 64));
        Instant now = clock.instant();
        created.setLastScannedAt(now);
        created.setDlapLastScannedTs(now);
        if (!event.technicalMetadata().isEmpty()) {
            created.setTechnicalMetadata(new HashMap<>(event.technicalMetadata()));
        }
        entityManager.persist(created);
        entityManager.flush();
        canonicalUriIndex.writeThrough(created.getAssetId(), created.getAssetUri(), created.getServiceType(),
                created.getCloudProvider(), created.getRegion());
        log.info("Catalog row created: asset_uri={} provider={} serviceType={}", created.getAssetUri(),
                created.getCloudProvider(), created.getServiceType());
        return created;
    }

    /**
     * The lifecycle time of a new row. A CREATE log event is a transition at its event time. A
     * crawler observation is not a transition (ADR-0055 item 7): the row takes the provider
     * creation time the crawler reports, when it is not after the crawl, and otherwise none,
     * so a delayed ARCHIVE or DELETE that happened before the crawl still applies.
     *
     * @param event     the creating event
     * @param eventTime its event time
     * @return the lifecycle time, or {@code null} when unknown (null counts as never)
     */
    static Instant initialLifecycleTime(CatalogEvent event, Instant eventTime) {
        if (!event.isCrawler()) {
            return eventTime;
        }
        return event.createdTime().filter(c -> !c.isAfter(eventTime)).orElse(null);
    }

    /**
     * Refreshes the descriptive metadata of an existing row from a discovery event: the
     * technical metadata is merged; a crawler observation also refreshes the name and region,
     * another event only fills them when blank; the native id is set once.
     *
     * @param asset the locked row
     * @param event the event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(Asset asset, CatalogEvent event) {
        Map<String, String> tech = event.technicalMetadata();
        if (!tech.isEmpty()) {
            Map<String, Object> merged = new HashMap<>();
            if (asset.getTechnicalMetadata() != null) {
                merged.putAll(asset.getTechnicalMetadata());
            }
            merged.putAll(tech);
            asset.setTechnicalMetadata(merged);
        }
        event.targetName().filter(n -> n.length() <= MAX_NAME)
                .filter(n -> event.isCrawler() || isBlank(asset.getAssetName()))
                .ifPresent(asset::setAssetName);
        event.targetRegion().filter(r -> r.length() <= MAX_REGION)
                .filter(r -> event.isCrawler() || isBlank(asset.getRegion()))
                .ifPresent(asset::setRegion);
        String raw = event.rawTargetId();
        if (asset.getNativeAssetId() == null && !raw.isBlank() && raw.length() <= MAX_NATIVE_ID) {
            asset.setNativeAssetId(raw);
        }
    }

    /**
     * Records that DLAP reconciled the row now (operational time, not a business time).
     *
     * @param asset the row
     */
    public void markScanned(Asset asset) {
        Instant now = clock.instant();
        asset.setLastScannedAt(now);
        asset.setDlapLastScannedTs(now);
    }

    /**
     * Publishes {@code ASSET_CREATED} or {@code ASSET_UPDATED} for the row once the current
     * transaction commits; without a transaction it publishes at once.
     *
     * @param asset     the row
     * @param eventType {@code ASSET_CREATED} or {@code ASSET_UPDATED}
     */
    public void publishAfterCommit(Asset asset, String eventType) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    assetService.publishAssetChangeEvent(asset, eventType, null);
                }
            });
        } else {
            assetService.publishAssetChangeEvent(asset, eventType, null);
        }
    }

    /** The event's name, else the last identity part of the canonical ID (bucket, table, job). */
    private static String nameFor(CatalogEvent event, CanonicalAssetId id) {
        String name = event.targetName().orElse(null);
        if (name == null) {
            List<String> parts = id.parts();
            name = parts.isEmpty() ? null : parts.get(parts.size() - 1);
        }
        return name != null && name.length() <= MAX_NAME ? name : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
