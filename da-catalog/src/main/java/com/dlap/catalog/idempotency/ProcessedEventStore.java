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

package com.dlap.catalog.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.common.logevent.LogEventIds;

/**
 * The catalog's processed-event table ({@code dlap_catalog.processed_event}, Flyway V3,
 * ADR-0055): the per-consumer dedup layer of docs/specs/log-event.md section 5.4.
 *
 * <p>{@link #claim} inserts one {@code (consumer, event key, asset)} row and reports whether
 * it was new. It must run inside the transaction that applies the change it guards
 * ({@link Propagation#MANDATORY}), so the claim and the change commit or roll back
 * together: a redelivered event finds its row and is skipped, and a failed change leaves
 * no row behind, so the retry applies it. Two replicas racing on the same pair serialize
 * on the primary key; the second insert conflicts after the first commits.
 */
@Component
public class ProcessedEventStore {

    /** Consumer of the typed lifecycle topics: asset status, activity times, creation, size. */
    public static final String CONSUMER_ASSET_STATE = "catalog-asset-state";
    /** Usage counters, keyed by {@code LogEventIds.usageDedupKey}. */
    public static final String CONSUMER_USAGE = "catalog-usage";
    /** Harvested business metadata from the normalized stream. */
    public static final String CONSUMER_ENRICHMENT = "catalog-enrichment";
    /** Lineage edges from the normalized stream, keyed by the downstream asset. */
    public static final String CONSUMER_LINEAGE = "catalog-lineage";

    /** Width of {@code processed_event.event_id}; longer keys are stored as their SHA-256. */
    static final int MAX_KEY_LENGTH = 128;

    private static final String INSERT_SQL = "INSERT INTO dlap_catalog.processed_event "
            + "(consumer, event_id, asset_id, event_time, processed_at) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (consumer, event_id, asset_id) DO NOTHING";

    private static final String EXISTS_SQL = "SELECT count(*) FROM dlap_catalog.processed_event "
            + "WHERE consumer = ? AND event_id = ? AND asset_id = ?";

    private static final String PRUNE_SQL = "DELETE FROM dlap_catalog.processed_event WHERE ctid IN ("
            + "SELECT ctid FROM dlap_catalog.processed_event WHERE processed_at < ? LIMIT ?)";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param jdbcTemplate JDBC access on the catalog data source (joins the JPA transaction)
     * @param clock        source of the operational {@code processed_at} time
     */
    public ProcessedEventStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Records that {@code consumer} applied {@code eventKey} to {@code assetId}.
     *
     * @param consumer  one of the {@code CONSUMER_*} names
     * @param eventKey  the event id or usage dedup key
     * @param assetId   the asset the change applies to
     * @param eventTime the event's business time, kept for diagnosis; may be {@code null}
     * @return {@code true} when the pair is new and the caller must apply the change;
     *         {@code false} when it was applied before and the caller must skip it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String consumer, String eventKey, UUID assetId, Instant eventTime) {
        requireArguments(consumer, eventKey, assetId);
        int inserted = jdbcTemplate.update(INSERT_SQL, consumer, storedKey(eventKey), assetId,
                utc(eventTime), utc(clock.instant()));
        return inserted == 1;
    }

    /**
     * Whether {@code consumer} already applied {@code eventKey} to {@code assetId}.
     *
     * @param consumer one of the {@code CONSUMER_*} names
     * @param eventKey the event id or usage dedup key
     * @param assetId  the asset
     * @return {@code true} when a row exists
     */
    public boolean isProcessed(String consumer, String eventKey, UUID assetId) {
        requireArguments(consumer, eventKey, assetId);
        Integer rows = jdbcTemplate.queryForObject(EXISTS_SQL, Integer.class, consumer, storedKey(eventKey), assetId);
        return rows != null && rows > 0;
    }

    /**
     * Deletes up to {@code batchSize} rows processed before {@code cutoff}. Safe to run on
     * every replica at once: each statement deletes a disjoint or empty set.
     *
     * @param cutoff    rows with {@code processed_at} before this instant go
     * @param batchSize maximum rows to delete in this call
     * @return the number of rows deleted
     */
    @Transactional
    public int pruneBefore(Instant cutoff, int batchSize) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return jdbcTemplate.update(PRUNE_SQL, utc(cutoff), batchSize);
    }

    /**
     * The value stored in {@code event_id}: the key itself, or {@code sha256:<hex>} when it is
     * longer than the column (legacy crawler event ids embed the asset URI).
     *
     * @param eventKey the key
     * @return at most {@value #MAX_KEY_LENGTH} characters
     */
    static String storedKey(String eventKey) {
        return eventKey.length() <= MAX_KEY_LENGTH ? eventKey : "sha256:" + LogEventIds.sha256Hex(eventKey);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static void requireArguments(String consumer, String eventKey, UUID assetId) {
        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("consumer must not be blank");
        }
        if (eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException("eventKey must not be blank");
        }
        Objects.requireNonNull(assetId, "assetId");
    }
}
