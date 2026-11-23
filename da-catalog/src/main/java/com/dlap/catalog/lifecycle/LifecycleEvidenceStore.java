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

package com.dlap.catalog.lifecycle;

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
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * JDBC access to the evidence tables of Flyway V6: {@code dlap_catalog.lifecycle_evidence} (the
 * Log Event ids behind each field of an asset) and {@code dlap_catalog.lifecycle_coverage_evidence}
 * (the applied read-coverage observations).
 *
 * <p>An evidence row is valid only while its {@code value_key} equals the asset's stored value;
 * writing evidence for a new value removes the rows of the old one, and the emitter ignores rows
 * whose value differs. So the tables never contradict the asset row: at worst they hold nothing,
 * and the emitter then reports the field as unknown.
 */
@Component
public class LifecycleEvidenceStore {

    /** A Log Event id: lowercase hex SHA-256 (docs/specs/log-event.md section 5.1). */
    public static final Pattern EVENT_ID = Pattern.compile("^[0-9a-f]{64}$");

    /** Width of {@code lifecycle_evidence.value_key}. */
    static final int MAX_VALUE_KEY_LENGTH = 600;

    /** Width of the {@code channel} column. */
    static final int MAX_CHANNEL_LENGTH = 64;

    /** Width of the {@code binding_id} column. */
    static final int MAX_BINDING_ID_LENGTH = 255;

    private static final String DELETE_OTHER_VALUES = "DELETE FROM dlap_catalog.lifecycle_evidence "
            + "WHERE asset_id = ? AND field = ? AND value_key <> ?";

    private static final String COUNT_NEWER = "SELECT count(*) FROM dlap_catalog.lifecycle_evidence "
            + "WHERE asset_id = ? AND field = ? AND event_time > ?";

    private static final String DELETE_OLDER = "DELETE FROM dlap_catalog.lifecycle_evidence "
            + "WHERE asset_id = ? AND field = ? AND event_time < ?";

    private static final String INSERT = "INSERT INTO dlap_catalog.lifecycle_evidence "
            + "(asset_id, field, value_key, event_id, event_time, channel, binding_id, executed_by, recorded_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (asset_id, field, event_id) DO NOTHING";

    private static final String DELETE_OVER_CAP = "DELETE FROM dlap_catalog.lifecycle_evidence "
            + "WHERE asset_id = ? AND field = ? AND event_id NOT IN ("
            + "SELECT event_id FROM dlap_catalog.lifecycle_evidence WHERE asset_id = ? AND field = ? "
            + "ORDER BY event_id LIMIT ?)";

    private static final String FIND = "SELECT asset_id, field, value_key, event_id, event_time, channel, "
            + "binding_id, executed_by FROM dlap_catalog.lifecycle_evidence WHERE asset_id = ANY (?) "
            + "ORDER BY asset_id, field, event_id";

    private static final String INSERT_COVERAGE = "INSERT INTO dlap_catalog.lifecycle_coverage_evidence "
            + "(event_id, provider, root_uri, applies_to, observed_at, recorded_at) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (event_id) DO NOTHING";

    private static final String DELETE_OLDER_COVERAGE = "DELETE FROM dlap_catalog.lifecycle_coverage_evidence "
            + "WHERE provider = ? AND root_uri = ? AND applies_to = ? AND observed_at < ?";

    private static final String FIND_COVERAGE = "SELECT event_id, provider, root_uri, applies_to, observed_at "
            + "FROM dlap_catalog.lifecycle_coverage_evidence WHERE provider = ? AND observed_at = ? "
            + "ORDER BY event_id";

    /** How a field keeps its evidence. */
    public enum Keep {
        /** Every applied event that carries the stored value (a maximum, a minimum, an observation). */
        ALL_WITH_VALUE,
        /** Only the latest events that carry the stored value (a provider setting read by every crawl). */
        LATEST_WITH_VALUE
    }

    /**
     * One evidence row.
     *
     * @param assetId    the asset
     * @param field      the field ({@code createdAt}, {@code lastReadAt}, ...)
     * @param valueKey   the value the event proves, as text
     * @param eventId    the Log Event id
     * @param eventTime  the event's business time
     * @param channel    the evidence channel, or {@code null}
     * @param bindingId  the log binding, or {@code null}
     * @param executedBy for a disposition: {@code native-rule}, {@code engine} or {@code other}
     */
    public record Evidence(UUID assetId, String field, String valueKey, String eventId, Instant eventTime,
                           String channel, String bindingId, String executedBy) {
    }

    /**
     * One applied read-coverage observation.
     *
     * @param eventId    the observation's event id
     * @param provider   the provider
     * @param rootUri    the root it applies under
     * @param appliesTo  the service types it applies to
     * @param observedAt when the configuration was read
     */
    public record CoverageEvidence(String eventId, String provider, String rootUri, List<String> appliesTo,
                                   Instant observedAt) {

        /**
         * Whether the observation applies to an asset (the match rule of the coverage applier: the
         * root itself or a URI under {@code root + "/"}, of a named service type and the provider).
         *
         * @param assetUri    the stored asset URI
         * @param provider    the asset's provider
         * @param serviceType the asset's service type
         * @return {@code true} when it applies
         */
        public boolean appliesTo(String assetUri, String provider, String serviceType) {
            return this.provider.equals(provider) && appliesTo.contains(serviceType)
                    && (assetUri.equals(rootUri) || assetUri.startsWith(rootUri + "/"));
        }
    }

    private static final RowMapper<CoverageEvidence> COVERAGE_MAPPER = (rs, n) -> new CoverageEvidence(
            rs.getString("event_id"), rs.getString("provider"), rs.getString("root_uri"),
            List.of(rs.getString("applies_to").split(",")), instant(rs, "observed_at"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param jdbc  JDBC access on the catalog data source (joins the caller's transaction)
     * @param clock source of the operational {@code recorded_at} time
     */
    public LifecycleEvidenceStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records that an event carries the value an asset's field now holds. Rows of any other value of
     * the field are removed first. Runs in the caller's transaction, which holds the asset's row
     * lock, so two events of one asset never interleave here.
     *
     * @param evidence the row; {@code eventId} must be a Log Event id
     * @param keep     how the field keeps its evidence
     * @param cap      most rows kept for the field; the lowest event ids stay
     * @throws IllegalArgumentException when the event id is not a Log Event id
     */
    public void record(Evidence evidence, Keep keep, int cap) {
        Objects.requireNonNull(evidence, "evidence");
        if (!EVENT_ID.matcher(evidence.eventId()).matches()) {
            throw new IllegalArgumentException("evidence needs a Log Event id");
        }
        if (evidence.valueKey().length() > MAX_VALUE_KEY_LENGTH
                || (evidence.bindingId() != null && evidence.bindingId().length() > MAX_BINDING_ID_LENGTH)) {
            // A cut value or binding id would be an invented one; without the row the field is unknown.
            return;
        }
        UUID asset = evidence.assetId();
        String field = evidence.field();
        jdbc.update(DELETE_OTHER_VALUES, asset, field, evidence.valueKey());
        OffsetDateTime time = utc(evidence.eventTime());
        if (keep == Keep.LATEST_WITH_VALUE) {
            Long newer = jdbc.queryForObject(COUNT_NEWER, Long.class, asset, field, time);
            if (newer != null && newer > 0) {
                return;
            }
            jdbc.update(DELETE_OLDER, asset, field, time);
        }
        jdbc.update(INSERT, asset, field, evidence.valueKey(), evidence.eventId(), time,
                fits(evidence.channel(), MAX_CHANNEL_LENGTH), fits(evidence.bindingId(), MAX_BINDING_ID_LENGTH),
                evidence.executedBy(), utc(clock.instant()));
        jdbc.update(DELETE_OVER_CAP, asset, field, asset, field, cap);
    }

    /**
     * Every evidence row of a set of assets.
     *
     * @param assetIds the assets
     * @return the rows by asset, each list in field and event id order; an asset without rows has no entry
     */
    public Map<UUID, List<Evidence>> findByAssets(Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Evidence>> byAsset = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(FIND);
            ps.setArray(1, con.createArrayOf("uuid", assetIds.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> {
            UUID id = rs.getObject("asset_id", UUID.class);
            byAsset.computeIfAbsent(id, k -> new ArrayList<>()).add(new Evidence(id, rs.getString("field"),
                    rs.getString("value_key"), rs.getString("event_id"), instant(rs, "event_time"),
                    rs.getString("channel"), rs.getString("binding_id"), rs.getString("executed_by")));
        });
        return byAsset;
    }

    /**
     * Records an applied read-coverage observation and drops older observations of the same root
     * and service types, which no asset can hold any more.
     *
     * @param evidence the observation; {@code eventId} must be a Log Event id
     * @throws IllegalArgumentException when the event id is not a Log Event id
     */
    public void recordCoverage(CoverageEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!EVENT_ID.matcher(evidence.eventId()).matches()) {
            throw new IllegalArgumentException("coverage evidence needs a Log Event id");
        }
        String appliesTo = String.join(",", evidence.appliesTo());
        OffsetDateTime at = utc(evidence.observedAt());
        jdbc.update(INSERT_COVERAGE, evidence.eventId(), evidence.provider(), evidence.rootUri(), appliesTo, at,
                utc(clock.instant()));
        jdbc.update(DELETE_OLDER_COVERAGE, evidence.provider(), evidence.rootUri(), appliesTo, at);
    }

    /**
     * The applied observations of a provider read at one instant.
     *
     * @param provider   the provider
     * @param observedAt the observation time an asset stores
     * @return the observations, in event id order
     */
    public List<CoverageEvidence> coverageAt(String provider, Instant observedAt) {
        return jdbc.query(FIND_COVERAGE, COVERAGE_MAPPER, provider, utc(observedAt));
    }

    /** The value when it is set and fits the column, else {@code null} (unknown), never a cut value. */
    private static String fits(String value, int width) {
        if (value == null || value.isBlank() || value.length() > width) {
            return null;
        }
        return value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
