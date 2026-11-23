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

package com.dlap.policyengine.dsl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The scheduled sweep's per-policy cursor, table {@code policy_schedule_state} (Flyway V6,
 * POL-01): the latest cron slot fired for each scheduled policy and the counts of that fire.
 * Plain JDBC with UTC timestamps, schema-qualified like the ShedLock table.
 *
 * <p>Every value is a bind parameter. The only text spliced into a statement is the table
 * identifier, validated once in the constructor against {@link #QUALIFIED_IDENTIFIER}; each
 * statement is built there, once, and the methods pass the finished constant to the template.
 */
@Repository
public class PolicyScheduleStore {

    /** A table identifier: a lower-case name, optionally qualified by one lower-case schema. */
    static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?$");

    private final JdbcTemplate jdbc;
    private final String findSql;
    private final String insertBaselineSql;
    private final String recordFireSql;

    /**
     * Creates the store.
     *
     * @param dataSource the service data source
     * @param schema     the service schema
     */
    public PolicyScheduleStore(DataSource dataSource,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_policyengine}") String schema) {
        this.jdbc = new JdbcTemplate(dataSource);
        String table = PolicyScheduleConfiguration.qualified(schema, "policy_schedule_state");
        if (!QUALIFIED_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table identifier for the policy schedule store: " + table);
        }
        this.findSql = "SELECT policy_id, last_slot, first_seen_at FROM " + table + " WHERE policy_id = ?";
        this.insertBaselineSql = "INSERT INTO " + table + " (policy_id, last_slot, first_seen_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (policy_id) DO NOTHING";
        this.recordFireSql = "UPDATE " + table + " SET last_slot = ?, last_started_at = ?, last_finished_at = ?, "
                + "assets_evaluated = ?, matched = ?, unknown = ?, published = ?, shadowed = ? WHERE policy_id = ?";
    }

    /**
     * The stored cursor of one policy.
     *
     * @param policyId    the policy row id
     * @param lastSlot    the latest cron slot recorded as fired (or the baseline)
     * @param firstSeenAt when the sweeper first saw the policy
     */
    public record State(UUID policyId, Instant lastSlot, Instant firstSeenAt) {
    }

    /**
     * Counts of one fire.
     *
     * @param assetsEvaluated assets the sweep evaluated for the policy
     * @param matched         assets whose match was {@code TRUE}
     * @param unknown         assets whose match was {@code UNKNOWN}
     * @param published       actions handed to the producer
     * @param shadowed        actions withheld by shadow mode
     * @param failed          actions whose send failed, before or after the hand-off, or was not
     *                        acknowledged in time (POL-02, Q-439). A fire with a failed send is not
     *                        recorded, so a stored fire always has {@code failed == 0} and the
     *                        table has no column for it; the count is logged and reported instead.
     */
    public record FireCounts(long assetsEvaluated, long matched, long unknown, long published, long shadowed,
            long failed) {

        /**
         * Counts of a fire without failed sends.
         *
         * @param assetsEvaluated assets evaluated
         * @param matched         {@code TRUE} matches
         * @param unknown         {@code UNKNOWN} matches
         * @param published       actions handed to the producer
         * @param shadowed        actions withheld by shadow mode
         */
        public FireCounts(long assetsEvaluated, long matched, long unknown, long published, long shadowed) {
            this(assetsEvaluated, matched, unknown, published, shadowed, 0);
        }
    }

    /**
     * Reads the cursor of a policy.
     *
     * @param policyId the policy row id
     * @return the cursor, or empty when the sweeper has not seen the policy
     */
    public Optional<State> find(UUID policyId) {
        List<State> rows = jdbc.query(findSql,
                (rs, i) -> new State(rs.getObject("policy_id", UUID.class),
                        rs.getObject("last_slot", OffsetDateTime.class).toInstant(),
                        rs.getObject("first_seen_at", OffsetDateTime.class).toInstant()),
                policyId);
        return rows.stream().findFirst();
    }

    /**
     * Records the first sight of a policy: its cursor starts at {@code baseline}, so it fires
     * from the first slot after it and never retroactively. A concurrent insert is ignored.
     *
     * @param policyId the policy row id
     * @param baseline the cursor start
     * @param seenAt   the sweep instant
     */
    public void insertBaseline(UUID policyId, Instant baseline, Instant seenAt) {
        jdbc.update(insertBaselineSql, policyId, utc(baseline), utc(seenAt));
    }

    /**
     * Records a finished fire: the cursor moves to {@code slot} and the counts are replaced.
     *
     * @param policyId   the policy row id
     * @param slot       the slot fired
     * @param startedAt  when the sweep started
     * @param finishedAt when it finished
     * @param counts     its counts
     */
    public void recordFire(UUID policyId, Instant slot, Instant startedAt, Instant finishedAt, FireCounts counts) {
        jdbc.update(recordFireSql,
                utc(slot), utc(startedAt), utc(finishedAt),
                counts.assetsEvaluated(), counts.matched(), counts.unknown(), counts.published(), counts.shadowed(),
                policyId);
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
