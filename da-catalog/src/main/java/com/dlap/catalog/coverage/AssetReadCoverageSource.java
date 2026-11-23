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

package com.dlap.catalog.coverage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.dlap.catalog.retention.BindingReadCoverageSource;
import com.dlap.catalog.retention.ReadCoverage;
import com.dlap.catalog.retention.ReadCoverageSource;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;

/**
 * The retention evaluator's read signal with per-asset coverage (COV-01): reads of an asset are
 * captured only when its stored coverage is {@code FULL} <em>and</em> the binding signal
 * ({@link BindingReadCoverageSource}) captures them.
 *
 * <p>The binding signal alone is per scope, but logging blind spots are per resource: a
 * CloudTrail selector that leaves out a bucket, an audit-config exemption, a diagnostic setting
 * without {@code StorageRead}. With this source a {@code PARTIAL}, {@code NONE} or unknown
 * coverage gives no read coverage, so a last-access rule stays {@code UNKNOWN} and nothing calls
 * the asset unread (critique correction 2).
 *
 * <p>The coverage window is the intersection of the two: it starts at the later of the binding's
 * start and {@code read_coverage_since} (the first of the current run of {@code FULL}
 * observations), and is complete up to the earlier of the binding's low watermark and the last
 * observation plus {@code dlap.catalog.coverage.validity-grace}. A configuration is known only
 * when it was read, so an expiry after the last observation is {@code READ_COVERAGE_STALE}.
 *
 * <p>Primary, so it replaces the binding-only source wherever a {@link ReadCoverageSource} is
 * injected. {@code dlap.catalog.coverage.require-asset-coverage=false} removes it and restores the
 * binding-only behaviour of CAT-03.
 */
@Component
@Primary
@ConditionalOnProperty(name = "dlap.catalog.coverage.require-asset-coverage", havingValue = "true",
        matchIfMissing = true)
public class AssetReadCoverageSource implements ReadCoverageSource {

    private static final String FULL_SQL = "SELECT asset_id, read_coverage_since, read_coverage_observed_at "
            + "FROM dlap_common.dlap_assets WHERE read_coverage = 'FULL' "
            + "AND read_coverage_since IS NOT NULL AND read_coverage_observed_at IS NOT NULL";

    private final BindingReadCoverageSource bindings;
    private final JdbcTemplate jdbc;
    private final ReadCoverageProperties properties;

    /**
     * Creates the source.
     *
     * @param bindings   the binding signal
     * @param jdbc       the catalog datasource
     * @param properties the coverage settings
     */
    public AssetReadCoverageSource(BindingReadCoverageSource bindings, JdbcTemplate jdbc,
                                   ReadCoverageProperties properties) {
        this.bindings = bindings;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** One asset's stored {@code FULL} coverage. */
    record Full(Instant since, Instant observedAt) {
    }

    @Override
    public View snapshot() {
        Map<UUID, Full> full = new HashMap<>();
        jdbc.query(FULL_SQL, rs -> {
            full.put(rs.getObject("asset_id", UUID.class),
                    new Full(rs.getObject("read_coverage_since", OffsetDateTime.class).toInstant(),
                            rs.getObject("read_coverage_observed_at", OffsetDateTime.class).toInstant()));
        });
        return view(full, bindings.snapshot(), properties);
    }

    /**
     * Builds the view from the stored coverage and the binding view.
     *
     * @param full       the assets whose stored coverage is {@code FULL}
     * @param bindings   the binding view
     * @param properties the settings
     * @return the combined view
     */
    static View view(Map<UUID, Full> full, View bindings, ReadCoverageProperties properties) {
        return (AssetRow asset) -> {
            Full f = asset.assetId() == null ? null : full.get(asset.assetId());
            if (f == null) {
                return Optional.empty();
            }
            return bindings.coverageFor(asset).map(b -> {
                Instant since = b.since().isAfter(f.since()) ? b.since() : f.since();
                Instant proven = f.observedAt().plus(properties.getValidityGrace());
                Instant validThrough = b.validThrough().isBefore(proven) ? b.validThrough() : proven;
                return new ReadCoverage(since, validThrough);
            });
        };
    }
}
