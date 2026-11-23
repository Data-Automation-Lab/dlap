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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional outbox of retention-due events ({@code dlap_catalog.retention_outbox}, Flyway
 * V4). The sweep writes an event in the transaction that changes {@code retention_status}, so the
 * change and its announcement commit or roll back together; {@link RetentionDuePublisher}
 * publishes the rows afterwards ("after the change commits", delivery at least once).
 */
@Component
public class RetentionOutbox {

    /**
     * A row waiting to be published.
     *
     * @param outboxId the sequence number; rows are published in this order
     * @param eventId  the event id
     * @param key      the Kafka key
     * @param payload  the JSON message
     */
    public record Pending(long outboxId, String eventId, String key, String payload) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the outbox.
     *
     * @param jdbcTemplate JDBC access on the catalog data source
     * @param clock        source of the operational {@code created_at} and {@code published_at}
     */
    public RetentionOutbox(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Queues an event. A transition that was queued before (same event id) is not queued again.
     *
     * @param event the event
     * @return {@code true} when the event was queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean add(RetentionDueEvent event) {
        return jdbcTemplate.update("INSERT INTO dlap_catalog.retention_outbox "
                + "(event_id, asset_uri, payload, created_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (event_id) DO NOTHING", event.eventId(), event.key(), event.payload(),
                utc(clock.instant())) == 1;
    }

    /**
     * The oldest events that have not been published.
     *
     * @param limit the most rows to return
     * @return the rows in publication order
     */
    public List<Pending> pending(int limit) {
        return jdbcTemplate.query("SELECT outbox_id, event_id, asset_uri, payload FROM dlap_catalog.retention_outbox "
                + "WHERE published_at IS NULL ORDER BY outbox_id LIMIT ?",
                (rs, n) -> new Pending(rs.getLong("outbox_id"), rs.getString("event_id"), rs.getString("asset_uri"),
                        rs.getString("payload")), limit);
    }

    /**
     * Marks rows as published.
     *
     * @param outboxIds the rows the broker acknowledged
     * @return how many rows were marked
     */
    public int markPublished(Collection<Long> outboxIds) {
        if (outboxIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(con -> {
            var ps = con.prepareStatement("UPDATE dlap_catalog.retention_outbox SET published_at = ? "
                    + "WHERE outbox_id = ANY (?) AND published_at IS NULL");
            ps.setObject(1, utc(clock.instant()));
            ps.setArray(2, con.createArrayOf("bigint", outboxIds.toArray()));
            return ps;
        });
    }

    /**
     * Deletes published rows older than the cutoff.
     *
     * @param cutoff rows published before this instant go
     * @return how many rows were deleted
     */
    public int pruneBefore(Instant cutoff) {
        return jdbcTemplate.update("DELETE FROM dlap_catalog.retention_outbox "
                + "WHERE published_at IS NOT NULL AND published_at < ?", utc(cutoff));
    }

    /**
     * How many events wait to be published.
     *
     * @return the backlog
     */
    public long backlog() {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM dlap_catalog.retention_outbox "
                + "WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
