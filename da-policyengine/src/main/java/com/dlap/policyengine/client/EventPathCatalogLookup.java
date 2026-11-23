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

package com.dlap.policyengine.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.dlap.policyengine.client.dto.CatalogAssetDTO;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * A bounded, short-lived cache of the catalog lookups on the policy event path (POL-05, decision D45,
 * Q-437). The lifecycle event consumer asks the catalog twice per event when a policy listens on the topic
 * (URI to asset id, then the asset); on {@code dlap.discovery.asset.accessed} that is one pair of calls per
 * read. This class answers repeats from memory.
 *
 * <p><b>What it holds.</b> Only answers: an asset by catalog id and an asset id by URI. A lookup that
 * found nothing (unknown asset, 404, catalog down or circuit open) is never stored, so an asset that has
 * just been created, or a catalog that has just recovered, is seen on the next event.
 *
 * <p><b>Freshness.</b> An entry is served only while its age on the injected business clock is at least zero
 * and strictly below the TTL ({@value #PROP_TTL}, default 30 s). An older entry, and an entry whose age is
 * negative because the clock stepped back, is a miss that is looked up again and replaced. The P2D coverage
 * freshness rule of ADR-0130 (two days) is therefore decided on catalog data at most one TTL old. The cache
 * is used on the event path only: the PDP and the scheduled sweep read the catalog directly.
 *
 * <p><b>Bound.</b> At most {@value #PROP_MAX_ENTRIES} entries (default 10,000) over both kinds of key; the
 * least recently used one is dropped to make room. A TTL of zero turns the cache off and every call goes to
 * the catalog.
 *
 * <p><b>Metrics.</b> {@value #COUNTER}{@code {lookup=asset|uri, result=hit|miss}} counts every call (a call
 * with the cache off counts as a miss) and the gauge {@value #GAUGE} is the number of entries held.
 */
@Component
@EnableConfigurationProperties(EventPathCatalogCacheProperties.class)
public class EventPathCatalogLookup {

    /** Property: how long an answer may be served. */
    public static final String PROP_TTL = "dlap.policyengine.catalog-cache.ttl";
    /** Property: the most entries held. */
    public static final String PROP_MAX_ENTRIES = "dlap.policyengine.catalog-cache.max-entries";
    /** Counter of lookups, tagged {@code lookup} and {@code result}. */
    public static final String COUNTER = "policyengine.catalog.cache.lookups";
    /** Gauge of the entries held. */
    public static final String GAUGE = "policyengine.catalog.cache.entries";

    private static final String LOOKUP_ASSET = "asset";
    private static final String LOOKUP_URI = "uri";

    private final ResilientCatalogAssetClient catalog;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final Map<Key, Entry> entries;
    private final Counter assetHits;
    private final Counter assetMisses;
    private final Counter uriHits;
    private final Counter uriMisses;

    /**
     * Creates the lookup from the {@code dlap.policyengine.catalog-cache.*} settings.
     *
     * @param catalog       the circuit-broken catalog client
     * @param clock         business clock; the age of an entry is read from it
     * @param meterRegistry metrics
     * @param properties    the TTL and the bound
     */
    @Autowired
    public EventPathCatalogLookup(ResilientCatalogAssetClient catalog, Clock clock, MeterRegistry meterRegistry,
            EventPathCatalogCacheProperties properties) {
        this(catalog, clock, meterRegistry, properties.ttl(), properties.maxEntries());
    }

    /**
     * Creates the lookup.
     *
     * @param catalog       the circuit-broken catalog client
     * @param clock         business clock; the age of an entry is read from it
     * @param meterRegistry metrics
     * @param ttl           how long an answer may be served; zero turns the cache off
     * @param maxEntries    the most entries held, at least 1
     */
    public EventPathCatalogLookup(ResilientCatalogAssetClient catalog, Clock clock, MeterRegistry meterRegistry,
            Duration ttl, int maxEntries) {
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException(PROP_TTL + " must not be negative");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException(PROP_MAX_ENTRIES + " must be at least 1, was " + maxEntries);
        }
        this.catalog = catalog;
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                return size() > EventPathCatalogLookup.this.maxEntries;
            }
        };
        this.assetHits = meterRegistry.counter(COUNTER, "lookup", LOOKUP_ASSET, "result", "hit");
        this.assetMisses = meterRegistry.counter(COUNTER, "lookup", LOOKUP_ASSET, "result", "miss");
        this.uriHits = meterRegistry.counter(COUNTER, "lookup", LOOKUP_URI, "result", "hit");
        this.uriMisses = meterRegistry.counter(COUNTER, "lookup", LOOKUP_URI, "result", "miss");
        meterRegistry.gauge(GAUGE, this, EventPathCatalogLookup::size);
    }

    /**
     * Fetches one asset, from the cache when an answer younger than the TTL is held.
     *
     * @param assetId the catalog id
     * @return the asset, or empty when the catalog has none or cannot answer
     */
    public Optional<CatalogAssetDTO> getAssetById(UUID assetId) {
        if (assetId == null) {
            return Optional.empty();
        }
        return lookup(new Key(LOOKUP_ASSET, assetId.toString()), assetHits, assetMisses, CatalogAssetDTO.class,
                key -> catalog.getAssetById(assetId));
    }

    /**
     * Resolves an asset URI to its catalog id, from the cache when an answer younger than the TTL is held.
     *
     * @param assetUri the URI, in any form the catalog normalises; the form is part of the key
     * @return the id, or empty when no asset has that URI or the catalog cannot answer
     */
    public Optional<UUID> resolveAssetIdByUri(String assetUri) {
        if (assetUri == null || assetUri.isBlank()) {
            return Optional.empty();
        }
        return lookup(new Key(LOOKUP_URI, assetUri.strip()), uriHits, uriMisses, UUID.class,
                key -> catalog.resolveAssetIdByUri(assetUri));
    }

    /**
     * The number of entries held, expired ones included until they are looked up or pushed out.
     *
     * @return the entry count
     */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** Drops every entry. */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private <T> Optional<T> lookup(Key key, Counter hits, Counter misses, Class<T> type,
            Function<Key, Optional<T>> load) {
        if (ttl.isZero()) {
            misses.increment();
            return load.apply(key);
        }
        Instant now = clock.instant();
        synchronized (entries) {
            Entry held = entries.get(key);
            if (held != null) {
                Duration age = Duration.between(held.storedAt(), now);
                if (!age.isNegative() && age.compareTo(ttl) < 0) {
                    hits.increment();
                    return Optional.of(type.cast(held.value()));
                }
                entries.remove(key);
            }
        }
        misses.increment();
        // The catalog call is made outside the lock, so a slow catalog never blocks the other listener threads.
        Optional<T> loaded = load.apply(key);
        if (loaded.isPresent()) {
            synchronized (entries) {
                entries.put(key, new Entry(loaded.get(), now));
            }
        }
        return loaded;
    }

    /** The key of one kind of lookup. */
    private record Key(String lookup, String text) {
    }

    /** One answer and the instant it was read from the catalog (taken before the call, so the age is never understated). */
    private record Entry(Object value, Instant storedAt) {
    }
}
