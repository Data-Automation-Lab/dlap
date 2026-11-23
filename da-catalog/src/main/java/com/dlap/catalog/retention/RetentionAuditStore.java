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

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The append-only audit trail of the retention actions that need a named role
 * ({@code dlap_catalog.retention_audit}, Flyway V4): placing and releasing a legal hold, adding
 * and removing a MANUAL rule. A row is written in the transaction of the action it records, so an
 * action that rolls back leaves no trail and an action that commits always has one. The row holds
 * who, what, when (DLAP's clock) and the operator's reason; it never holds a credential.
 */
@Component
public class RetentionAuditStore {

    /** The audited actions. */
    public static final class Action {
        /** A legal hold was placed. */
        public static final String HOLD_PLACED = "HOLD_PLACED";
        /** A legal hold was released. */
        public static final String HOLD_RELEASED = "HOLD_RELEASED";
        /** An operator added a MANUAL rule. */
        public static final String RULE_ADDED = "RULE_ADDED";
        /** An operator removed a MANUAL rule. */
        public static final String RULE_REMOVED = "RULE_REMOVED";

        private Action() {
        }
    }

    /**
     * One audit row.
     *
     * @param assetId    the asset
     * @param action     one of {@link Action}
     * @param actor      the operator's user name
     * @param actorId    the operator's id, or {@code null}
     * @param reason     the operator's reason, or {@code null}
     * @param detail     what changed, for example the rule, or {@code null}
     * @param occurredAt when, by DLAP's clock
     */
    public record Entry(UUID assetId, String action, String actor, UUID actorId, String reason, String detail,
                        java.time.Instant occurredAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param jdbcTemplate JDBC access on the catalog data source
     * @param clock        the time source for {@code occurred_at}
     */
    public RetentionAuditStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Records an action. Must run in the transaction of the action it records.
     *
     * @param assetId the asset
     * @param action  one of {@link Action}
     * @param actor   the operator's user name
     * @param actorId the operator's id, or {@code null}
     * @param reason  the operator's reason, or {@code null}
     * @param detail  what changed, or {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID assetId, String action, String actor, UUID actorId, String reason, String detail) {
        jdbcTemplate.update("INSERT INTO dlap_catalog.retention_audit "
                + "(asset_id, action, actor, actor_id, reason, detail, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                assetId, action, actor, actorId, reason, detail, clock.instant().atOffset(ZoneOffset.UTC));
    }

    /**
     * The trail of one asset, oldest first.
     *
     * @param assetId the asset
     * @return the entries
     */
    public List<Entry> findByAsset(UUID assetId) {
        return jdbcTemplate.query("SELECT asset_id, action, actor, actor_id, reason, detail, occurred_at "
                + "FROM dlap_catalog.retention_audit WHERE asset_id = ? ORDER BY audit_id",
                (rs, n) -> new Entry(rs.getObject("asset_id", UUID.class), rs.getString("action"),
                        rs.getString("actor"), rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                        rs.getString("detail"),
                        rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()), assetId);
    }
}
