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

package com.dlap.catalog.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily usage counters ({@code dlap_catalog.asset_usage_daily}, Flyway V3, ADR-0055).
 *
 * <p>The day is the UTC date of the event's {@code event_time} (business time, never the
 * consumer clock). The store only adds; deduplication is the caller's job, through a
 * {@code processed_event} claim on the usage dedup key in the same transaction
 * ({@link Propagation#MANDATORY}), so the counters are idempotent under redelivery.
 */
@Component
public class AssetUsageDailyStore {

    private static final String UPSERT_SQL = "INSERT INTO dlap_catalog.asset_usage_daily "
            + "(asset_id, day, reads, writes, first_event_at, last_event_at) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (asset_id, day) DO UPDATE SET "
            + "reads = dlap_catalog.asset_usage_daily.reads + EXCLUDED.reads, "
            + "writes = dlap_catalog.asset_usage_daily.writes + EXCLUDED.writes, "
            + "first_event_at = LEAST(dlap_catalog.asset_usage_daily.first_event_at, EXCLUDED.first_event_at), "
            + "last_event_at = GREATEST(dlap_catalog.asset_usage_daily.last_event_at, EXCLUDED.last_event_at)";

    private static final String SELECT_SQL = "SELECT asset_id, day, reads, writes, first_event_at, last_event_at "
            + "FROM dlap_catalog.asset_usage_daily WHERE asset_id = ? AND day BETWEEN ? AND ? ORDER BY day";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the store.
     *
     * @param jdbcTemplate JDBC access on the catalog data source (joins the JPA transaction)
     */
    public AssetUsageDailyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Adds one successful recorded read on the day of {@code eventTime}.
     *
     * @param assetId   the asset (after roll-up)
     * @param eventTime the read's event time
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addRead(UUID assetId, Instant eventTime) {
        add(assetId, eventTime, 1, 0);
    }

    /**
     * Adds one successful recorded write on the day of {@code eventTime}.
     *
     * @param assetId   the asset (after roll-up)
     * @param eventTime the write's event time
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addWrite(UUID assetId, Instant eventTime) {
        add(assetId, eventTime, 0, 1);
    }

    /**
     * Reads the counters of one asset for an inclusive range of UTC days.
     *
     * @param assetId the asset
     * @param from    first day
     * @param to      last day
     * @return one row per day that has counts, oldest first
     */
    public List<UsageDay> findDays(UUID assetId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(assetId, "assetId");
        return jdbcTemplate.query(SELECT_SQL, (rs, i) -> new UsageDay(
                rs.getObject("asset_id", UUID.class),
                rs.getObject("day", LocalDate.class),
                rs.getLong("reads"),
                rs.getLong("writes"),
                toInstant(rs.getObject("first_event_at", OffsetDateTime.class)),
                toInstant(rs.getObject("last_event_at", OffsetDateTime.class))),
                assetId, from, to);
    }

    /**
     * The usage day of an event time.
     *
     * @param eventTime the event time
     * @return its UTC date
     */
    public static LocalDate dayOf(Instant eventTime) {
        return eventTime.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private void add(UUID assetId, Instant eventTime, int reads, int writes) {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(eventTime, "eventTime");
        OffsetDateTime at = eventTime.atOffset(ZoneOffset.UTC);
        jdbcTemplate.update(UPSERT_SQL, assetId, dayOf(eventTime), reads, writes, at, at);
    }

    private static Instant toInstant(OffsetDateTime ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * One day of counters.
     *
     * @param assetId      the asset
     * @param day          the UTC day
     * @param reads        successful recorded reads
     * @param writes       successful recorded writes
     * @param firstEventAt earliest counted event time that day
     * @param lastEventAt  latest counted event time that day
     */
    public record UsageDay(UUID assetId, LocalDate day, long reads, long writes,
                           Instant firstEventAt, Instant lastEventAt) {
    }
}
