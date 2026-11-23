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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a re-key run will change: computed from every {@code dlap_assets} row by
 * {@link AssetUriRekeyPlanner}, executed by {@link AssetUriRekeyExecutor}.
 *
 * @param scanned   rows read
 * @param unchanged rows already canonical, with the service type and provider of their canonical ID
 * @param changes   rows alone under their canonical URI whose identity changes in place
 * @param groups    collision groups: rows that normalize to one URI and are merged into the oldest
 * @param rejected  rows whose stored URI does not normalize; the run leaves them as they are
 */
public record RekeyPlan(int scanned, int unchanged, List<Change> changes, List<MergeGroup> groups,
                        List<RejectedRow> rejected) {

    /**
     * Copies the lists.
     *
     * @param scanned   rows read
     * @param unchanged rows already canonical
     * @param changes   in-place changes
     * @param groups    collision groups
     * @param rejected  rows that do not normalize
     */
    public RekeyPlan {
        changes = List.copyOf(changes);
        groups = List.copyOf(groups);
        rejected = List.copyOf(rejected);
    }

    /**
     * Whether the run would change nothing (a second run after an apply).
     *
     * @return the flag
     */
    public boolean isEmpty() {
        return changes.isEmpty() && groups.isEmpty();
    }

    /**
     * Rows merged away (all group members but the survivor).
     *
     * @return the count
     */
    public int mergedRows() {
        return groups.stream().mapToInt(g -> g.merged().size()).sum();
    }

    /**
     * Every stored URI the run replaces or deletes.
     *
     * @return the old URIs
     */
    public List<String> oldUris() {
        List<String> out = new ArrayList<>();
        for (Change c : changes) {
            if (!c.row().assetUri().equals(c.target().uri())) {
                out.add(c.row().assetUri());
            }
        }
        for (MergeGroup g : groups) {
            if (!g.survivor().assetUri().equals(g.target().uri())) {
                out.add(g.survivor().assetUri());
            }
            g.merged().forEach(m -> out.add(m.assetUri()));
        }
        return out;
    }

    /**
     * A catalog row as the planner reads it.
     *
     * @param assetId       the row
     * @param assetUri      the stored URI
     * @param serviceType   the stored service type
     * @param cloudProvider the stored provider
     * @param region        the stored region (a normalization hint)
     * @param nativeAssetId the stored native id
     * @param createdTs     the row's creation time; with {@code assetId} it orders a collision group
     */
    public record StoredAsset(UUID assetId, String assetUri, String serviceType, String cloudProvider, String region,
                              String nativeAssetId, OffsetDateTime createdTs) {

        /**
         * Validates the required fields.
         *
         * @param assetId       the row
         * @param assetUri      the stored URI
         * @param serviceType   the stored service type
         * @param cloudProvider the stored provider
         * @param region        the region
         * @param nativeAssetId the native id
         * @param createdTs     the creation time
         */
        public StoredAsset {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(assetUri, "assetUri");
        }
    }

    /**
     * The identity a row gets: from its canonical ID.
     *
     * @param uri           the canonical URI
     * @param serviceType   the canonical kind's service type
     * @param cloudProvider the canonical provider
     */
    public record Target(String uri, String serviceType, String cloudProvider) {
    }

    /**
     * An in-place identity change of a row that is alone under its canonical URI.
     *
     * @param row    the row
     * @param target its new identity
     */
    public record Change(StoredAsset row, Target target) {

        /**
         * Whether the service type or provider changes, not only the URI.
         *
         * @return the flag
         */
        public boolean kindChange() {
            return !Objects.equals(row.serviceType(), target.serviceType())
                    || !Objects.equals(row.cloudProvider(), target.cloudProvider());
        }
    }

    /**
     * Rows that normalize to one canonical URI. The survivor is the oldest row (by
     * {@code created_ts}, then {@code asset_id}), the row the catalog event matcher already
     * picks; the rest are merged into it.
     *
     * @param target   the identity the survivor gets
     * @param survivor the oldest row
     * @param merged   the other rows, oldest first
     */
    public record MergeGroup(Target target, StoredAsset survivor, List<StoredAsset> merged) {

        /**
         * Copies the list.
         *
         * @param target   the identity
         * @param survivor the oldest row
         * @param merged   the other rows
         */
        public MergeGroup {
            merged = List.copyOf(merged);
        }

        /**
         * Whether the members disagree on service type (for example a Glue job stored once as
         * SPARK_JOB and once as PYTHON_JOB); the survivor's canonical kind wins.
         *
         * @return the flag
         */
        public boolean kindConflict() {
            return merged.stream().anyMatch(m -> !Objects.equals(m.serviceType(), survivor.serviceType()));
        }

        /**
         * The members, survivor first.
         *
         * @return the rows
         */
        public List<StoredAsset> members() {
            List<StoredAsset> all = new ArrayList<>(merged.size() + 1);
            all.add(survivor);
            all.addAll(merged);
            return all;
        }
    }

    /**
     * A row whose stored URI does not normalize.
     *
     * @param row    the row
     * @param reason the rejection reason ({@code AssetIdParseResult.Reason} name)
     */
    public record RejectedRow(StoredAsset row, String reason) {
    }
}
