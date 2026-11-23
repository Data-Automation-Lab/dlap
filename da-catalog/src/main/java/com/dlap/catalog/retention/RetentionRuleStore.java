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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.dlap.common.model.RetentionBasis;

/**
 * The retention rule store ({@code dlap_catalog.retention_rule}, Flyway V4, ADR-0046 decision 7):
 * the rules that live in the catalog's own schema, {@link RuleSource#POLICY} rules recorded from
 * {@code setRetention} actions and {@link RuleSource#MANUAL} rules written by an operator. The
 * evaluator reads them on every run; nothing is ever copied into {@code dlap_assets}.
 *
 * <p>Writes join the caller's transaction when there is one, so a rule change and the
 * re-evaluation it triggers commit together.
 */
@Component
public class RetentionRuleStore {

    /**
     * A stored rule with the facts the API shows.
     *
     * @param rule      the rule, with its row id
     * @param assetId   the asset
     * @param reason    the operator's reason, or {@code null}
     * @param issuedAt  the policy action's {@code firedAt}, or when the operator created the rule
     * @param createdBy the operator of a MANUAL rule, or {@code null}
     */
    public record StoredRule(RetentionRule rule, UUID assetId, String reason, Instant issuedAt, String createdBy) {
    }

    private static final String COLUMNS = "rule_id, asset_id, source, basis, days, expires_at, policy_id, mode, "
            + "reason, issued_at, created_by";

    private static final String UPSERT_POLICY_SQL = "INSERT INTO dlap_catalog.retention_rule "
            + "(rule_id, asset_id, source, basis, days, expires_at, policy_id, mode, reason, issued_at, "
            + "created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'POLICY', ?, ?, NULL, ?, ?, NULL, ?, NULL, ?, ?) "
            + "ON CONFLICT (asset_id, policy_id) WHERE source = 'POLICY' DO UPDATE SET "
            + "basis = EXCLUDED.basis, days = EXCLUDED.days, mode = EXCLUDED.mode, "
            + "issued_at = EXCLUDED.issued_at, updated_at = EXCLUDED.updated_at "
            + "WHERE dlap_catalog.retention_rule.issued_at <= EXCLUDED.issued_at";

    private static final String INSERT_MANUAL_SQL = "INSERT INTO dlap_catalog.retention_rule "
            + "(rule_id, asset_id, source, basis, days, expires_at, policy_id, mode, reason, issued_at, "
            + "created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'MANUAL', ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)";

    private static final RowMapper<StoredRule> MAPPER = RetentionRuleStore::map;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param jdbcTemplate JDBC access on the catalog data source (joins the JPA transaction)
     * @param clock        source of the operational {@code created_at} and {@code updated_at}
     */
    public RetentionRuleStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Records a policy's {@code setRetention} action. A repeat of the same action is a no-op, an
     * action of the same policy that was issued later replaces the rule, and one issued earlier
     * than the stored rule is ignored.
     *
     * @param assetId  the asset
     * @param policyId the policy engine's policy id
     * @param basis    a day-based basis
     * @param days     the period
     * @param mode     {@code soft}, {@code hard} or {@code null}
     * @param issuedAt when the policy fired; orders the actions of one policy
     * @return {@code true} when the rule was inserted or replaced
     */
    public boolean upsertPolicyRule(UUID assetId, UUID policyId, RetentionBasis basis, int days, String mode,
                                    Instant issuedAt) {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(policyId, "policyId");
        if (!basis.requiresDays()) {
            throw new IllegalArgumentException("A setRetention rule is day-based, not " + basis);
        }
        OffsetDateTime now = utc(clock.instant());
        int rows = jdbcTemplate.update(UPSERT_POLICY_SQL, UUID.randomUUID(), assetId, basis.name(), days, policyId,
                mode, utc(issuedAt), now, now);
        return rows == 1;
    }

    /**
     * Adds an operator's rule.
     *
     * @param assetId   the asset
     * @param basis     the basis
     * @param days      the period; {@code null} exactly for {@code FIXED_DATE}
     * @param expiresAt the expiry; set exactly for {@code FIXED_DATE}
     * @param reason    why, at most 1000 characters; may be {@code null}
     * @param createdBy the operator's user name
     * @return the stored rule
     */
    public StoredRule insertManual(UUID assetId, RetentionBasis basis, Integer days, Instant expiresAt,
                                   String reason, String createdBy) {
        RetentionRule shape = new RetentionRule(UUID.randomUUID(), RuleSource.MANUAL, basis, days, expiresAt, null,
                null);
        Instant now = clock.instant();
        jdbcTemplate.update(INSERT_MANUAL_SQL, shape.ruleId(), assetId, basis.name(), days, utc(expiresAt), reason,
                utc(now), createdBy, utc(now), utc(now));
        return new StoredRule(shape, assetId, reason, now, createdBy);
    }

    /**
     * Deletes an operator's rule. A policy's rule cannot be deleted here.
     *
     * @param assetId the asset
     * @param ruleId  the rule
     * @return the deleted rule, or empty when no MANUAL rule of the asset has that id
     */
    public java.util.Optional<StoredRule> deleteManual(UUID assetId, UUID ruleId) {
        List<StoredRule> found = jdbcTemplate.query("SELECT " + COLUMNS + " FROM dlap_catalog.retention_rule "
                + "WHERE rule_id = ? AND asset_id = ? AND source = 'MANUAL'", MAPPER, ruleId, assetId);
        if (found.isEmpty()) {
            return java.util.Optional.empty();
        }
        jdbcTemplate.update("DELETE FROM dlap_catalog.retention_rule WHERE rule_id = ? AND asset_id = ? "
                + "AND source = 'MANUAL'", ruleId, assetId);
        return java.util.Optional.of(found.get(0));
    }

    /**
     * Every stored rule of one asset, oldest first.
     *
     * @param assetId the asset
     * @return the rules
     */
    public List<StoredRule> findByAsset(UUID assetId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM dlap_catalog.retention_rule WHERE asset_id = ? "
                + "ORDER BY issued_at, rule_id", MAPPER, assetId);
    }

    /**
     * The stored rules of a set of assets, for one page of the sweep.
     *
     * @param assetIds the assets
     * @return the rules by asset; an asset without rules has no entry
     */
    public Map<UUID, List<RetentionRule>> findByAssets(Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        List<StoredRule> rows = jdbcTemplate.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLUMNS + " FROM dlap_catalog.retention_rule "
                    + "WHERE asset_id = ANY (?)");
            ps.setArray(1, con.createArrayOf("uuid", assetIds.toArray()));
            return ps;
        }, MAPPER);
        Map<UUID, List<RetentionRule>> byAsset = new HashMap<>();
        for (StoredRule row : rows) {
            byAsset.computeIfAbsent(row.assetId(), k -> new ArrayList<>()).add(row.rule());
        }
        return byAsset;
    }

    private static StoredRule map(ResultSet rs, int rowNum) throws SQLException {
        RetentionRule rule = new RetentionRule(
                rs.getObject("rule_id", UUID.class),
                RuleSource.valueOf(rs.getString("source")),
                RetentionBasis.valueOf(rs.getString("basis")),
                rs.getObject("days", Integer.class),
                instant(rs, "expires_at"),
                rs.getObject("policy_id", UUID.class),
                rs.getString("mode"));
        return new StoredRule(rule, rs.getObject("asset_id", UUID.class), rs.getString("reason"),
                instant(rs, "issued_at"), rs.getString("created_by"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
