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
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The latest {@code dlap.log.v1.LogBindingStatus} per binding ({@code dlap_catalog.log_binding_status},
 * Flyway V4): the read signal of the retention evaluator. The topic is compacted, so the latest
 * message per binding is its current state; a status reported earlier than the stored one never
 * replaces it, so redelivery and reordering are harmless.
 */
@Component
public class BindingStatusStore {

    /**
     * A binding's status as the evaluator needs it.
     *
     * @param bindingId         the binding
     * @param sourceProvider    the {@code SourceProvider} name ({@code GCP}, {@code AWS}, ...)
     * @param sourceKind        the source kind, for example {@code GCP_CLOUD_AUDIT}
     * @param scopeType         the scope type, for example {@code GCP_PROJECT}
     * @param scopeId           the account, project, subscription or workspace id
     * @param state             the {@code BindingState} name
     * @param readCoverage      the {@code ReadCoverage} name
     * @param readCoverageSince earliest event time from which reads are known to be captured
     * @param lowWatermark      event time before which everything is acknowledged
     * @param highWatermark     newest event time seen
     * @param reportedTime      when the status was computed
     */
    public record BindingStatus(String bindingId, String sourceProvider, String sourceKind, String scopeType,
                                String scopeId, String state, String readCoverage, Instant readCoverageSince,
                                Instant lowWatermark, Instant highWatermark, Instant reportedTime) {
    }

    private static final String UPSERT_SQL = "INSERT INTO dlap_catalog.log_binding_status "
            + "(binding_id, source_provider, source_kind, scope_type, scope_id, state, read_coverage, "
            + "read_coverage_since, low_watermark, high_watermark, reported_time, config_hash, received_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (binding_id) DO UPDATE SET source_provider = EXCLUDED.source_provider, "
            + "source_kind = EXCLUDED.source_kind, scope_type = EXCLUDED.scope_type, scope_id = EXCLUDED.scope_id, "
            + "state = EXCLUDED.state, read_coverage = EXCLUDED.read_coverage, "
            + "read_coverage_since = EXCLUDED.read_coverage_since, low_watermark = EXCLUDED.low_watermark, "
            + "high_watermark = EXCLUDED.high_watermark, reported_time = EXCLUDED.reported_time, "
            + "config_hash = EXCLUDED.config_hash, received_at = EXCLUDED.received_at "
            + "WHERE dlap_catalog.log_binding_status.reported_time <= EXCLUDED.reported_time";

    private static final RowMapper<BindingStatus> MAPPER = BindingStatusStore::map;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param jdbcTemplate JDBC access on the catalog data source
     * @param clock        source of the operational {@code received_at}
     */
    public BindingStatusStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Stores a binding's status unless the stored one was reported later.
     *
     * @param status     the status
     * @param configHash the binding's effective-config hash, or {@code null}
     * @return {@code true} when the row was inserted or replaced, {@code false} when a later
     *         report is already stored
     */
    public boolean upsert(BindingStatus status, String configHash) {
        int rows = jdbcTemplate.update(UPSERT_SQL, status.bindingId(), status.sourceProvider(), status.sourceKind(),
                status.scopeType(), status.scopeId(), status.state(), status.readCoverage(),
                utc(status.readCoverageSince()), utc(status.lowWatermark()), utc(status.highWatermark()),
                utc(status.reportedTime()), configHash, utc(clock.instant()));
        return rows == 1;
    }

    /**
     * Removes a binding (a tombstone on the compacted topic): its coverage no longer counts.
     *
     * @param bindingId the binding
     * @return {@code true} when a row was removed
     */
    public boolean delete(String bindingId) {
        return jdbcTemplate.update("DELETE FROM dlap_catalog.log_binding_status WHERE binding_id = ?",
                bindingId) == 1;
    }

    /**
     * Every stored binding status. The table has one row per binding, so it is small.
     *
     * @return the statuses
     */
    public List<BindingStatus> findAll() {
        return jdbcTemplate.query("SELECT binding_id, source_provider, source_kind, scope_type, scope_id, state, "
                + "read_coverage, read_coverage_since, low_watermark, high_watermark, reported_time "
                + "FROM dlap_catalog.log_binding_status ORDER BY binding_id", MAPPER);
    }

    private static BindingStatus map(ResultSet rs, int rowNum) throws SQLException {
        return new BindingStatus(rs.getString("binding_id"), rs.getString("source_provider"),
                rs.getString("source_kind"), rs.getString("scope_type"), rs.getString("scope_id"),
                rs.getString("state"), rs.getString("read_coverage"), instant(rs, "read_coverage_since"),
                instant(rs, "low_watermark"), instant(rs, "high_watermark"), instant(rs, "reported_time"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
