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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.dlap.common.assetid.CanonicalAssetId;

/**
 * Finds the catalog row an event target names. Reads go through JDBC, so they do not put
 * entities into the persistence context before the caller locks the row it will change.
 *
 * <ol>
 *   <li><b>exact</b>: a row whose {@code asset_uri} is the canonical URI (rows written since
 *       CAT-02a, and crawler rows that were already canonical);</li>
 *   <li><b>normalized</b>: a row whose stored {@code asset_uri} normalizes to the canonical URI
 *       ({@link CanonicalUriIndex}); with duplicates, the oldest row wins, the same choice
 *       ID-03 makes when it merges them;</li>
 *   <li><b>raw</b>: only when the target did not normalize, a row whose {@code asset_uri} is
 *       the raw identifier and whose provider is the event's, so a bare name never matches
 *       another cloud's row.</li>
 * </ol>
 * On a miss the index is built or caught up and the normalized lookup runs once more.
 */
@Component
public class AssetMatcher {

    /** How a row was found. */
    public enum Method {
        /** {@code asset_uri} equals the canonical URI. */
        EXACT,
        /** The stored {@code asset_uri} normalizes to the canonical URI. */
        NORMALIZED,
        /** The target did not normalize; {@code asset_uri} equals the raw identifier. */
        RAW
    }

    /**
     * A found row.
     *
     * @param assetId the row
     * @param method  how it was found
     */
    public record Match(UUID assetId, Method method) {
    }

    private static final String BY_URI_SQL = "SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri = ?";
    private static final String BY_URI_AND_PROVIDER_SQL =
            "SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri = ? AND cloud_provider = ?";
    private static final String STORED_URI_SQL = "SELECT asset_uri FROM dlap_common.dlap_assets WHERE asset_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final CanonicalUriIndex index;
    private final CatalogIngestMetrics metrics;

    /**
     * Creates the matcher.
     *
     * @param jdbcTemplate JDBC access
     * @param index        the normalized-URI index
     * @param metrics      the consumer metrics
     */
    public AssetMatcher(JdbcTemplate jdbcTemplate, CanonicalUriIndex index, CatalogIngestMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.index = index;
        this.metrics = metrics;
    }

    /**
     * Finds the row of a canonical ID.
     *
     * @param canonical the canonical ID
     * @return the row, or empty
     */
    public Optional<Match> find(CanonicalAssetId canonical) {
        Optional<UUID> exact = first(jdbcTemplate.queryForList(BY_URI_SQL, UUID.class, canonical.uri()));
        if (exact.isPresent()) {
            return found(exact.get(), Method.EXACT);
        }
        Optional<UUID> normalized = first(index.candidates(canonical.uri()));
        if (normalized.isEmpty() && (index.ensureBuilt() || index.catchUpIfDue())) {
            normalized = first(index.candidates(canonical.uri()));
        }
        return normalized.flatMap(id -> found(id, Method.NORMALIZED));
    }

    /**
     * Finds a row by an identifier that did not normalize, restricted to the event's provider.
     *
     * @param rawId         the identifier as the producer sent it
     * @param cloudProvider the event's provider ({@code dlap_assets.cloud_provider} value); without
     *                      one there is no raw match
     * @return the row, or empty
     */
    public Optional<Match> findRaw(String rawId, String cloudProvider) {
        if (rawId == null || rawId.isBlank() || cloudProvider == null || cloudProvider.isBlank()) {
            return Optional.empty();
        }
        return first(jdbcTemplate.queryForList(BY_URI_AND_PROVIDER_SQL, UUID.class, rawId, cloudProvider))
                .flatMap(id -> found(id, Method.RAW));
    }

    /**
     * Builds the normalized-URI index if this process has not, or waits for the build in
     * progress. Listeners call it before they open the record's transaction, so a consumer
     * that waits for the startup build holds no database connection while the build needs
     * them; inside the transaction {@link #find} then finds the index built.
     */
    public void awaitIndex() {
        index.ensureBuilt();
    }

    /**
     * The {@code asset_uri} stored for a row, for callers that pass URIs on (lineage).
     *
     * @param assetId the row
     * @return the stored URI, or empty when the row is gone
     */
    public Optional<String> storedUri(UUID assetId) {
        return first(jdbcTemplate.queryForList(STORED_URI_SQL, String.class, assetId));
    }

    private Optional<Match> found(UUID id, Method method) {
        metrics.matched(method.name().toLowerCase(java.util.Locale.ROOT));
        return Optional.of(new Match(id, method));
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }
}
