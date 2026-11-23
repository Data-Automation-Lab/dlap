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

package com.dlap.catalog.retention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * JDBC access to the columns of {@code dlap_common.dlap_assets} that the retention sweep reads
 * and writes. It reads only the evidence columns and a handful of {@code technical_metadata} keys
 * (not whole entities) and writes only the {@code retention_*} result columns, so a sweep never
 * rewrites the JSON columns of an asset and never touches a column another consumer owns.
 *
 * <p>{@link #lockById} takes the row lock ({@code FOR UPDATE}) that the event consumers also
 * take before they change an asset, so a sweep and a consumer never overwrite each other's
 * columns. The sweep holds one lock at a time.
 */
@Component
public class RetentionAssetStore {

    /** The result columns of the last evaluation, as stored. */
    public record StoredOutcome(RetentionStatus status, Integer days, RetentionBasis basis, String source,
                                UUID policyId, Instant expiresAt, Instant evaluatedAt) {

        /**
         * Whether an evaluation would write exactly these result columns (the as-of instant is
         * not part of the result).
         *
         * @param evaluation the evaluation
         * @return {@code true} when status, deciding rule and expiry are the same
         */
        public boolean sameResultAs(RetentionEvaluation evaluation) {
            return status == evaluation.status()
                    && java.util.Objects.equals(days, evaluation.retentionDays())
                    && basis == evaluation.retentionBasis()
                    && java.util.Objects.equals(source, evaluation.retentionSource())
                    && java.util.Objects.equals(policyId, evaluation.retentionPolicyId())
                    && java.util.Objects.equals(expiresAt, evaluation.expiresAt());
        }
    }

    /**
     * One asset as the sweep needs it.
     *
     * @param assetId        the asset
     * @param assetUri       the stored URI (canonical for rows since CAT-02a)
     * @param cloudProvider  the provider
     * @param serviceType    the kind
     * @param status         the lifecycle status ({@code ACTIVE}, ...), or {@code null}
     * @param cloudCreatedAt provider creation time
     * @param discoveredAt   first seen by DLAP
     * @param lastAccessedAt latest read
     * @param lastModifiedAt latest write
     * @param legalHold      the DLAP legal hold
     * @param metadata       the {@code technical_metadata} keys retention reads
     * @param current        the stored result of the last evaluation
     */
    public record AssetRow(UUID assetId, String assetUri, String cloudProvider, String serviceType, String status,
                           Instant cloudCreatedAt, Instant discoveredAt, Instant lastAccessedAt,
                           Instant lastModifiedAt, boolean legalHold, TechnicalKeys metadata,
                           StoredOutcome current) {
    }

    /**
     * The {@code technical_metadata} keys retention reads, as text: provider retention settings
     * recorded by crawlers (docs/specs/asset-descriptor.md "Provider rules") and the identifiers
     * that locate the asset's log binding.
     *
     * @param retentionPeriodDays provider retention period in days
     * @param expirationTime      provider expiry instant (ISO-8601)
     * @param projectId           GCP project
     * @param accountId           AWS or Snowflake account
     * @param subscriptionId      Azure subscription
     * @param workspaceId         Databricks workspace
     */
    public record TechnicalKeys(String retentionPeriodDays, String expirationTime, String projectId,
                                String accountId, String subscriptionId, String workspaceId) {
    }

    private static final String COLUMNS = "a.asset_id, a.asset_uri, a.cloud_provider, a.service_type, a.status, "
            + "a.cloud_created_at, COALESCE(a.discovered_at, a.dlap_discovered_ts) AS discovered_at, "
            + "a.last_accessed_at, a.last_modified_at, a.legal_hold, "
            + "a.technical_metadata ->> 'retentionPeriodDays' AS tm_retention_period_days, "
            + "a.technical_metadata ->> 'expirationTime' AS tm_expiration_time, "
            + "a.technical_metadata ->> 'projectId' AS tm_project_id, "
            + "a.technical_metadata ->> 'accountId' AS tm_account_id, "
            + "a.technical_metadata ->> 'subscriptionId' AS tm_subscription_id, "
            + "a.technical_metadata ->> 'workspaceId' AS tm_workspace_id, "
            + "a.retention_status, a.retention_days, a.retention_basis, a.retention_source, "
            + "a.retention_policy_id, a.retention_expires_at, a.retention_evaluated_at";

    private static final String PAGE_SQL = "SELECT " + COLUMNS + " FROM dlap_common.dlap_assets a "
            + "WHERE a.asset_id > ? AND a.status IS DISTINCT FROM 'DELETED' ORDER BY a.asset_id LIMIT ?";

    private static final String LOCK_SQL = "SELECT " + COLUMNS + " FROM dlap_common.dlap_assets a "
            + "WHERE a.asset_id = ? FOR UPDATE OF a";

    private static final String FIND_SQL = "SELECT " + COLUMNS + " FROM dlap_common.dlap_assets a "
            + "WHERE a.asset_id = ?";

    private static final String LABELS_SQL = "SELECT x.asset_id, l.label_name "
            + "FROM dlap_common.dlap_asset_label_assignments x "
            + "JOIN dlap_common.dlap_labels l ON l.label_id = x.label_id WHERE x.asset_id = ANY (?)";

    private static final String WRITE_SQL = "UPDATE dlap_common.dlap_assets SET retention_status = ?, "
            + "retention_days = ?, retention_basis = ?, retention_source = ?, retention_policy_id = ?, "
            + "retention_expires_at = ?, retention_evaluated_at = ? WHERE asset_id = ?";

    private static final RowMapper<AssetRow> MAPPER = RetentionAssetStore::map;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the store.
     *
     * @param jdbcTemplate JDBC access on the catalog data source (joins the JPA transaction)
     */
    public RetentionAssetStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * The next page of assets that are not {@code DELETED}, in {@code asset_id} order. Keyset
     * paging: a row changed or inserted during the sweep can neither be skipped nor repeated.
     *
     * @param after the last id of the previous page, or the all-zero UUID for the first page
     * @param limit the page size
     * @return up to {@code limit} rows with ids greater than {@code after}
     */
    public List<AssetRow> page(UUID after, int limit) {
        return jdbcTemplate.query(PAGE_SQL, MAPPER, after, limit);
    }

    /**
     * Reads one asset and locks its row until the surrounding transaction ends.
     *
     * @param assetId the asset
     * @return the row, or empty when it does not exist
     */
    public Optional<AssetRow> lockById(UUID assetId) {
        return jdbcTemplate.query(LOCK_SQL, MAPPER, assetId).stream().findFirst();
    }

    /**
     * Reads one asset without locking it.
     *
     * @param assetId the asset
     * @return the row, or empty when it does not exist
     */
    public Optional<AssetRow> findById(UUID assetId) {
        return jdbcTemplate.query(FIND_SQL, MAPPER, assetId).stream().findFirst();
    }

    /**
     * The label names of a set of assets, lower-cased.
     *
     * @param assetIds the assets
     * @return the names by asset; an asset without labels has no entry
     */
    public Map<UUID, Set<String>> labelNames(Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> byAsset = new HashMap<>();
        jdbcTemplate.query(con -> {
            var ps = con.prepareStatement(LABELS_SQL);
            ps.setArray(1, con.createArrayOf("uuid", assetIds.toArray()));
            return ps;
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> byAsset
                .computeIfAbsent(rs.getObject("asset_id", UUID.class), k -> new HashSet<>())
                .add(rs.getString("label_name").toLowerCase(Locale.ROOT)));
        return byAsset;
    }

    /**
     * Writes the result of an evaluation to the {@code retention_*} columns, and nothing else.
     *
     * @param assetId    the asset (its row is locked by the caller)
     * @param evaluation the result
     * @param asOf       the evaluation instant, stored as {@code retention_evaluated_at}
     */
    public void writeOutcome(UUID assetId, RetentionEvaluation evaluation, Instant asOf) {
        jdbcTemplate.update(WRITE_SQL, evaluation.status().name(), evaluation.retentionDays(),
                evaluation.retentionBasis() == null ? null : evaluation.retentionBasis().name(),
                evaluation.retentionSource(), evaluation.retentionPolicyId(),
                utc(evaluation.expiresAt()), utc(asOf), assetId);
    }

    private static AssetRow map(ResultSet rs, int rowNum) throws SQLException {
        StoredOutcome current = new StoredOutcome(
                status(rs.getString("retention_status")),
                rs.getObject("retention_days", Integer.class),
                basis(rs.getString("retention_basis")),
                rs.getString("retention_source"),
                rs.getObject("retention_policy_id", UUID.class),
                instant(rs, "retention_expires_at"),
                instant(rs, "retention_evaluated_at"));
        TechnicalKeys keys = new TechnicalKeys(rs.getString("tm_retention_period_days"),
                rs.getString("tm_expiration_time"), rs.getString("tm_project_id"), rs.getString("tm_account_id"),
                rs.getString("tm_subscription_id"), rs.getString("tm_workspace_id"));
        return new AssetRow(rs.getObject("asset_id", UUID.class), rs.getString("asset_uri"),
                rs.getString("cloud_provider"), rs.getString("service_type"), rs.getString("status"),
                instant(rs, "cloud_created_at"), instant(rs, "discovered_at"), instant(rs, "last_accessed_at"),
                instant(rs, "last_modified_at"), rs.getBoolean("legal_hold"), keys, current);
    }

    private static RetentionStatus status(String value) {
        if (value == null) {
            return RetentionStatus.UNKNOWN;
        }
        try {
            return RetentionStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return RetentionStatus.UNKNOWN;
        }
    }

    private static RetentionBasis basis(String value) {
        if (value == null) {
            return null;
        }
        try {
            return RetentionBasis.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
