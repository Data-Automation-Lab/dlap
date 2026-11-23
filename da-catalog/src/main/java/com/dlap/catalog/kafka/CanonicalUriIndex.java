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

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.logevent.LogEventIds;

/**
 * {@code normalize(existing asset_uri)} for every catalog row, kept in
 * {@code dlap_catalog.asset_uri_canonical} (Flyway V3, ADR-0055), so an event that names a
 * resource by its canonical URI finds a row stored in an older form (a GCS bucket stored as
 * {@code b}, a BigQuery table as {@code p:d.t}) instead of creating a duplicate. This is the
 * plan-critic item 10 rule; ID-03 later re-keys the stored rows.
 *
 * <p>The index is derived state and self-healing:
 * <ul>
 *   <li>a background build at startup, and on the first miss if it has not finished;</li>
 *   <li>write-through for rows the catalog consumers create, in their transaction;</li>
 *   <li>a catch-up sweep on a miss, at most once per
 *       {@code dlap.catalog.ingest.match.catch-up-interval} per replica, for rows other
 *       writers added (the asset API);</li>
 *   <li>an entry whose recorded {@code asset_uri} no longer equals the row's is stale and is
 *       recomputed before use.</li>
 * </ul>
 * Sweeps and stale-entry repairs run in their own transaction, so a failed event never undoes
 * them and a sweep never holds an event's row locks. Every replica may sweep at once: entries are upserts keyed
 * by {@code asset_id}.
 */
@Component
public class CanonicalUriIndex {

    private static final Logger log = LoggerFactory.getLogger(CanonicalUriIndex.class);

    /** Upper bound on rows normalized by one catch-up sweep. */
    static final int MAX_CATCH_UP_BATCHES = 20;

    private static final String CANDIDATES_SQL = "SELECT i.asset_id, i.asset_uri AS indexed_uri, a.asset_uri, "
            + "a.service_type, a.cloud_provider, a.region FROM dlap_catalog.asset_uri_canonical i "
            + "JOIN dlap_common.dlap_assets a ON a.asset_id = i.asset_id "
            + "WHERE i.canonical_sha256 = ? AND i.canonical_uri = ? ORDER BY a.created_ts, a.asset_id";

    private static final String PENDING_SQL = "SELECT a.asset_id, a.asset_uri, a.service_type, a.cloud_provider, "
            + "a.region FROM dlap_common.dlap_assets a "
            + "LEFT JOIN dlap_catalog.asset_uri_canonical i ON i.asset_id = a.asset_id "
            + "WHERE i.asset_id IS NULL OR i.asset_uri <> a.asset_uri ORDER BY a.asset_id LIMIT ?";

    private static final String UPSERT_SQL = "INSERT INTO dlap_catalog.asset_uri_canonical "
            + "(asset_id, asset_uri, canonical_uri, canonical_sha256, reject_reason, indexed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (asset_id) DO UPDATE SET "
            + "asset_uri = EXCLUDED.asset_uri, canonical_uri = EXCLUDED.canonical_uri, "
            + "canonical_sha256 = EXCLUDED.canonical_sha256, reject_reason = EXCLUDED.reject_reason, "
            + "indexed_at = EXCLUDED.indexed_at";

    private final JdbcTemplate jdbcTemplate;
    private final CatalogEventDecoder decoder;
    private final CatalogIngestProperties properties;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;

    private final ReentrantLock buildLock = new ReentrantLock();
    private volatile boolean built;
    private volatile Instant lastCatchUp = Instant.EPOCH;

    /**
     * Creates the index.
     *
     * @param jdbcTemplate       JDBC access
     * @param decoder            the normalizer front end (row hints, default hints)
     * @param properties         batch size and catch-up interval
     * @param clock              the time source for {@code indexed_at} and the catch-up spacing
     * @param transactionManager the transaction manager; index writes use their own transaction
     */
    public CanonicalUriIndex(JdbcTemplate jdbcTemplate, CatalogEventDecoder decoder,
                             CatalogIngestProperties properties, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.decoder = decoder;
        this.properties = properties;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Starts the initial build in the background once the application is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void buildOnStartup() {
        if (!properties.getMatch().isIndexOnStartup()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ensureBuilt();
            } catch (DataAccessException e) {
                log.warn("Canonical-URI index build failed at startup; the first miss retries: {}", e.getMessage());
            }
        }, "catalog-uri-index-build");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Rows whose stored URI normalizes to {@code canonicalUri}, oldest first. Stale entries are
     * recomputed and dropped when they no longer match.
     *
     * @param canonicalUri a canonical URI
     * @return matching asset ids, possibly several when duplicates exist before ID-03
     */
    public List<UUID> candidates(String canonicalUri) {
        List<UUID> out = new ArrayList<>();
        List<Row> rows = jdbcTemplate.query(CANDIDATES_SQL, (rs, i) -> new Row(
                rs.getObject("asset_id", UUID.class), rs.getString("asset_uri"), rs.getString("indexed_uri"),
                rs.getString("service_type"), rs.getString("cloud_provider"), rs.getString("region")),
                LogEventIds.sha256Hex(canonicalUri), canonicalUri);
        for (Row row : rows) {
            if (row.assetUri().equals(row.indexedUri())) {
                out.add(row.assetId());
                continue;
            }
            AssetIdParseResult now = upsertInOwnTransaction(row);
            if (now.id().map(id -> id.uri().equals(canonicalUri)).orElse(false)) {
                out.add(row.assetId());
            }
        }
        return out;
    }

    /**
     * Records the canonical form of one row the caller just wrote, in the caller's transaction,
     * so the entry rolls back with the row.
     *
     * @param assetId       the row
     * @param assetUri      its stored URI
     * @param serviceType   its service type
     * @param cloudProvider its provider
     * @param region        its region
     */
    public void writeThrough(UUID assetId, String assetUri, String serviceType, String cloudProvider, String region) {
        upsert(new Row(assetId, assetUri, null, serviceType, cloudProvider, region));
    }

    /**
     * Builds the index once per process: normalizes every row that has no current entry.
     * Concurrent callers wait for the first one.
     *
     * @return {@code true} when this call did the build (so a retry of the lookup is useful)
     */
    public boolean ensureBuilt() {
        if (built) {
            return false;
        }
        buildLock.lock();
        try {
            if (built) {
                return false;
            }
            int total = 0;
            int batch;
            do {
                batch = sweepBatch();
                total += batch;
            } while (batch > 0);
            built = true;
            lastCatchUp = clock.instant();
            log.info("Canonical-URI index built: {} row(s) normalized", total);
            return true;
        } finally {
            buildLock.unlock();
        }
    }

    /**
     * Indexes rows added since the last sweep, at most once per catch-up interval.
     *
     * @return {@code true} when rows were indexed (so a retry of the lookup is useful)
     */
    public boolean catchUpIfDue() {
        Instant now = clock.instant();
        if (now.isBefore(lastCatchUp.plus(properties.getMatch().getCatchUpInterval()))) {
            return false;
        }
        if (!buildLock.tryLock()) {
            return false;
        }
        try {
            lastCatchUp = now;
            int total = 0;
            for (int i = 0; i < MAX_CATCH_UP_BATCHES; i++) {
                int batch = sweepBatch();
                total += batch;
                if (batch < properties.getMatch().getIndexBatchSize()) {
                    break;
                }
            }
            if (total > 0) {
                log.debug("Canonical-URI index caught up {} row(s)", total);
            }
            return total > 0;
        } finally {
            buildLock.unlock();
        }
    }

    /**
     * Whether the initial build finished in this process.
     *
     * @return the flag
     */
    public boolean isBuilt() {
        return built;
    }

    private int sweepBatch() {
        Integer done = ownTransaction.execute(status -> {
            List<Row> pending = jdbcTemplate.query(PENDING_SQL, (rs, i) -> new Row(
                    rs.getObject("asset_id", UUID.class), rs.getString("asset_uri"), null,
                    rs.getString("service_type"), rs.getString("cloud_provider"), rs.getString("region")),
                    properties.getMatch().getIndexBatchSize());
            for (Row row : pending) {
                upsert(row);
            }
            return pending.size();
        });
        return done == null ? 0 : done;
    }

    private AssetIdParseResult upsertInOwnTransaction(Row row) {
        return ownTransaction.execute(status -> upsert(row));
    }

    private AssetIdParseResult upsert(Row row) {
        AssetIdParseResult result = decoder.normalizeStored(row.assetUri(), row.serviceType(), row.cloudProvider(),
                row.region());
        String canonical = result.id().map(id -> id.uri()).orElse(null);
        String reason = result instanceof AssetIdParseResult.Rejected r ? r.reason().name() : null;
        OffsetDateTime at = clock.instant().atOffset(ZoneOffset.UTC);
        jdbcTemplate.update(UPSERT_SQL, row.assetId(), row.assetUri(), canonical,
                canonical == null ? null : LogEventIds.sha256Hex(canonical), reason, at);
        return result;
    }

    private record Row(UUID assetId, String assetUri, String indexedUri, String serviceType, String cloudProvider,
                       String region) {
    }
}
