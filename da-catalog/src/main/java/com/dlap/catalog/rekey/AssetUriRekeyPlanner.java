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

package com.dlap.catalog.rekey;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.dlap.catalog.kafka.CatalogEventDecoder;
import com.dlap.catalog.rekey.RekeyPlan.Change;
import com.dlap.catalog.rekey.RekeyPlan.MergeGroup;
import com.dlap.catalog.rekey.RekeyPlan.RejectedRow;
import com.dlap.catalog.rekey.RekeyPlan.StoredAsset;
import com.dlap.catalog.rekey.RekeyPlan.Target;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.CanonicalAssetId;

/**
 * Builds the {@link RekeyPlan}: normalizes every stored {@code asset_uri} exactly as the catalog
 * event matcher does ({@link CatalogEventDecoder#normalizeStored}: the row's service type, provider
 * and region as hints, plus the configured default hints), so the re-key and the matcher agree on
 * which rows are one asset. Rows are read oldest first, so the first row of a collision group is
 * the survivor, the same row {@code AssetMatcher} returns for that URI.
 */
@Component
public class AssetUriRekeyPlanner {

    private static final String ROWS_SQL = "SELECT asset_id, asset_uri, service_type, cloud_provider, region, "
            + "native_asset_id, created_ts FROM dlap_common.dlap_assets ORDER BY created_ts, asset_id";

    private final JdbcTemplate jdbcTemplate;
    private final CatalogEventDecoder decoder;

    /**
     * Creates the planner.
     *
     * @param jdbcTemplate JDBC access
     * @param decoder      the normalizer front end shared with the event matcher
     */
    public AssetUriRekeyPlanner(JdbcTemplate jdbcTemplate, CatalogEventDecoder decoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.decoder = decoder;
    }

    /**
     * Reads every catalog row and plans the run, in the caller's transaction.
     *
     * @return the plan
     */
    public RekeyPlan plan() {
        List<StoredAsset> rows = jdbcTemplate.query(ROWS_SQL, (rs, i) -> new StoredAsset(
                rs.getObject("asset_id", UUID.class), rs.getString("asset_uri"), rs.getString("service_type"),
                rs.getString("cloud_provider"), rs.getString("region"), rs.getString("native_asset_id"),
                rs.getObject("created_ts", OffsetDateTime.class)));
        return plan(rows, row -> decoder.normalizeStored(row.assetUri(), row.serviceType(), row.cloudProvider(),
                row.region()));
    }

    /**
     * Plans a run over rows given oldest first.
     *
     * @param rowsOldestFirst the rows, ordered by {@code created_ts} then {@code asset_id}
     * @param normalizer      the normalization of one stored row
     * @return the plan
     */
    static RekeyPlan plan(List<StoredAsset> rowsOldestFirst, Function<StoredAsset, AssetIdParseResult> normalizer) {
        Map<String, List<StoredAsset>> byUri = new LinkedHashMap<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        List<RejectedRow> rejected = new ArrayList<>();
        for (StoredAsset row : rowsOldestFirst) {
            AssetIdParseResult result = normalizer.apply(row);
            if (result instanceof AssetIdParseResult.Rejected r) {
                rejected.add(new RejectedRow(row, r.reason().name()));
                continue;
            }
            CanonicalAssetId id = result.id().orElseThrow();
            byUri.computeIfAbsent(id.uri(), k -> new ArrayList<>()).add(row);
            targets.putIfAbsent(id.uri(), new Target(id.uri(), id.serviceType(), id.cloudProvider()));
        }
        int unchanged = 0;
        List<Change> changes = new ArrayList<>();
        List<MergeGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<StoredAsset>> e : byUri.entrySet()) {
            Target target = targets.get(e.getKey());
            List<StoredAsset> members = e.getValue();
            if (members.size() > 1) {
                groups.add(new MergeGroup(target, members.get(0), members.subList(1, members.size())));
                continue;
            }
            Change change = new Change(members.get(0), target);
            if (change.row().assetUri().equals(target.uri()) && !change.kindChange()) {
                unchanged++;
            } else {
                changes.add(change);
            }
        }
        return new RekeyPlan(rowsOldestFirst.size(), unchanged, changes, groups, rejected);
    }
}
