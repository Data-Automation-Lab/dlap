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

package com.dlap.logingest.health;

import com.dlap.logingest.health.HealthSnapshot.ObservedVersion;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read queries behind the health snapshot: cursors, format observations and the read signal of
 * stored events. Every value is a bind parameter. The only text spliced into a statement is a table
 * identifier, validated once in the constructor; each statement is built there, once, and the
 * query methods pass the finished constant to the template.
 */
@Repository
public class HealthSnapshotRepository {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** A table identifier: a lower-case name, optionally qualified by one lower-case schema. */
    static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?$");

    /**
     * The watermarks of one stream, over its partitions.
     *
     * @param bindingId the binding
     * @param streamId  the stream
     * @param low       the oldest unacknowledged event time over the partitions, or {@code null}
     * @param high      the newest event time seen, or {@code null}
     * @param updatedAt when a cursor of the stream last moved
     */
    public record Cursor(String bindingId, String streamId, Instant low, Instant high, Instant updatedAt) {
    }

    /**
     * The read signal of one binding in stored events.
     *
     * @param bindingId the binding
     * @param lastRead  the latest ingest time of a data-read event, or {@code null}
     * @param lastWrite the latest ingest time of a data-write event, or {@code null}
     */
    public record ReadSeed(String bindingId, Instant lastRead, Instant lastWrite) {
    }

    private final JdbcTemplate jdbc;
    private final String cursorsSql;
    private final String observationsSql;
    private final String readSeedsSql;
    private final String firstReadSql;

    /**
     * Creates the repository.
     *
     * @param jdbc   the service datasource's template
     * @param schema the service schema
     * @throws IllegalArgumentException when the schema is not a plain SQL identifier
     */
    public HealthSnapshotRepository(JdbcTemplate jdbc,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_logingest}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the health queries: " + schema);
        }
        String cursors = identifier(schema + ".stream_cursor");
        String observations = identifier(schema + ".format_observation");
        String events = identifier(schema + ".log_events");
        this.jdbc = jdbc;
        this.cursorsSql = "SELECT binding_id, stream_id, min(low_watermark) AS low, max(high_watermark) AS high, "
                + "max(updated_at) AS updated FROM " + cursors + " GROUP BY binding_id, stream_id";
        this.observationsSql = "SELECT binding_id, envelope, schema_version, fingerprint, first_seen, last_seen, "
                + "record_count FROM " + observations + " ORDER BY binding_id, envelope, schema_version, fingerprint";
        this.readSeedsSql = "SELECT binding_id, "
                + "max(ingest_time) FILTER (WHERE event_category = 'EVENT_CATEGORY_DATA_READ') AS last_read, "
                + "max(ingest_time) FILTER (WHERE event_category = 'EVENT_CATEGORY_DATA_WRITE') AS last_write "
                + "FROM " + events + " WHERE ingest_time >= ? AND binding_id IS NOT NULL GROUP BY binding_id";
        this.firstReadSql = "SELECT min(event_time) AS first_read FROM " + events
                + " WHERE binding_id = ? AND event_category = 'EVENT_CATEGORY_DATA_READ'";
    }

    private static String identifier(String name) {
        if (!QUALIFIED_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid table identifier for the health queries: " + name);
        }
        return name;
    }

    /**
     * The cursor of every stream that has one.
     *
     * @return one entry per (binding, stream)
     */
    public List<Cursor> cursors() {
        return jdbc.query(cursorsSql,
                (rs, i) -> new Cursor(rs.getString("binding_id"), rs.getString("stream_id"),
                        instant(rs.getObject("low", OffsetDateTime.class)),
                        instant(rs.getObject("high", OffsetDateTime.class)),
                        instant(rs.getObject("updated", OffsetDateTime.class))));
    }

    /**
     * The formats and versions every binding delivered.
     *
     * @return binding id to its observations
     */
    public Map<String, List<ObservedVersion>> observations() {
        Map<String, List<ObservedVersion>> out = new LinkedHashMap<>();
        jdbc.query(observationsSql, rs -> {
                    out.computeIfAbsent(rs.getString("binding_id"), k -> new ArrayList<>()).add(new ObservedVersion(
                            rs.getString("envelope"), rs.getString("schema_version"), rs.getString("fingerprint"),
                            instant(rs.getObject("first_seen", OffsetDateTime.class)),
                            instant(rs.getObject("last_seen", OffsetDateTime.class)), rs.getLong("record_count")));
                });
        return out;
    }

    /**
     * Reads the read signal of stored events ingested since an instant. The scan is bounded by the
     * partition key ({@code ingest_time}) and runs once per start, because the telemetry keeps it up
     * to date afterwards.
     *
     * @param since the start of the lookback
     * @return one entry per binding that stored an event since then
     */
    public List<ReadSeed> readSeeds(Instant since) {
        return jdbc.query(readSeedsSql,
                (rs, i) -> new ReadSeed(rs.getString("binding_id"),
                        instant(rs.getObject("last_read", OffsetDateTime.class)),
                        instant(rs.getObject("last_write", OffsetDateTime.class))),
                OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
    }

    /**
     * The earliest event time of a data-read event of a binding.
     *
     * @param bindingId the binding
     * @return the event time, or empty when the binding stored no read
     */
    public Optional<Instant> firstReadEventTime(String bindingId) {
        return jdbc.query(firstReadSql,
                (rs, i) -> instant(rs.getObject("first_read", OffsetDateTime.class)), bindingId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
