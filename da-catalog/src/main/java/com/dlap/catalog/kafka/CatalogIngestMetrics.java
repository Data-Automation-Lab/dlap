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

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counters of the catalog event consumers. Tag values come from closed sets (provider and
 * kind enum names, category names, fixed outcome words), never from asset identifiers, so
 * cardinality stays bounded. Prometheus names are the meter names with dots as underscores
 * plus {@code _total}.
 */
@Component
public class CatalogIngestMetrics {

    /**
     * ADR-0046: events whose (rolled-up) target matches no catalog row. Tags {@code provider},
     * {@code kind} ({@code UNRESOLVED} when the identifier did not normalize) and
     * {@code category}. Prometheus: {@code dlap_catalog_unmatched_target_total}.
     */
    public static final String UNMATCHED_TARGET = "dlap.catalog.unmatched.target";

    /** Targets that did not normalize, by rejection reason. Prometheus: {@code dlap_catalog_unresolved_target_total}. */
    public static final String UNRESOLVED_TARGET = "dlap.catalog.unresolved.target";

    /** Outcome per consumer: applied, duplicate, unmatched, skipped. Prometheus: {@code dlap_catalog_events_total}. */
    public static final String EVENTS = "dlap.catalog.events";

    /** How a target found its row: exact, normalized (pre-canonical row), raw. Prometheus: {@code dlap_catalog_asset_match_total}. */
    public static final String ASSET_MATCH = "dlap.catalog.asset.match";

    /** Payloads read through the codec, by consumer and whether the form was legacy. */
    public static final String DECODED = "dlap.catalog.log.events.decoded";

    private final MeterRegistry registry;

    /**
     * Creates the metrics.
     *
     * @param registry the meter registry
     */
    public CatalogIngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Counts an event whose target matches no row.
     *
     * @param provider provider tag
     * @param kind     kind tag, or {@code UNRESOLVED}
     * @param category category tag
     */
    public void unmatched(String provider, String kind, String category) {
        registry.counter(UNMATCHED_TARGET, "provider", provider, "kind", kind, "category", category).increment();
    }

    /**
     * Counts a target that did not normalize.
     *
     * @param provider provider tag
     * @param reason   the {@code AssetIdParseResult.Reason} name
     */
    public void unresolved(String provider, String reason) {
        registry.counter(UNRESOLVED_TARGET, "provider", provider, "reason", reason).increment();
    }

    /**
     * Counts one consumer outcome.
     *
     * @param consumer the processed-event consumer name
     * @param outcome  applied, duplicate, unmatched or skipped
     */
    public void outcome(String consumer, String outcome) {
        registry.counter(EVENTS, "consumer", consumer, "outcome", outcome).increment();
    }

    /**
     * Counts how a row was found.
     *
     * @param method exact, normalized or raw
     */
    public void matched(String method) {
        registry.counter(ASSET_MATCH, "method", method).increment();
    }

    /**
     * Counts one decoded payload.
     *
     * @param consumer the consumer
     * @param legacy   whether the payload used a legacy form
     */
    public void decoded(String consumer, boolean legacy) {
        registry.counter(DECODED, "consumer", consumer, "legacy", Boolean.toString(legacy)).increment();
    }
}
