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

package com.dlap.logingest.quarantine;

import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code dlap_logingest.quarantine_record} (Flyway V3, design B3). Plain SQL
 * through {@link JdbcTemplate}, like the other log-layer stores: the writes are one idempotent
 * upsert, one marker update and a batched delete, and the reads are filtered, paged queries.
 *
 * <p>The table holds the masked, bounded excerpt, never the raw record. Every value reaches SQL as
 * a bind parameter. The only text spliced into a statement is the table identifier, validated once in
 * the constructor; each fixed statement is built there, once, and the query methods pass the finished
 * constant to the template. The filtered queries add a WHERE clause made only of constant fragments
 * and {@code ?} placeholders (see {@link #where}).
 */
@Repository
public class QuarantineRecordRepository {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** A table identifier: a lower-case name, optionally qualified by one lower-case schema. */
    static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /**
     * The filter of a query.
     *
     * @param bindingId       only this binding, or {@code null}
     * @param reason          only this reason code, or {@code null}
     * @param detectedVersion only this detected version, or {@code null}
     * @param replayed        {@code true} for resolved rows, {@code false} for pending ones, {@code null} for both
     * @param from            quarantined at or after this instant, or {@code null}
     * @param to              quarantined before this instant, or {@code null}
     * @param ids             only these quarantine ids, or {@code null}
     */
    public record Filter(String bindingId, String reason, String detectedVersion, Boolean replayed, Instant from,
                         Instant to, Collection<String> ids) {

        /** No restriction. */
        public static final Filter ALL = new Filter(null, null, null, null, null, null, null);
    }

    /**
     * A reason and how many pending rows carry it.
     *
     * @param reason the reason code
     * @param count  rows
     */
    public record ReasonCount(String reason, long count) {
    }

    private static final String COLUMNS = "quarantine_id, binding_id, source_provider, source_kind, envelope, "
            + "detected_version, pinned_version, parser_version, reason_code, reason_detail, raw_ref::text AS raw_ref, "
            + "raw_sha256, raw_size_bytes, raw_excerpt, raw_truncated, event_time, native_event_id, "
            + "pass_through_event_id, quarantined_at, replayed_at, replay_count";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String upsertSql;
    private final String findByIdSql;
    private final String selectHead;
    private final String countHead;
    private final String markReplaySql;
    private final String deleteBeforeSql;
    private final String topReasonsSql;
    private final String samplesSql;
    private final String countsByBindingSql;
    private final RowMapper<QuarantineRow> rows = this::mapRow;

    /**
     * Creates the repository.
     *
     * @param jdbc   the service datasource's template
     * @param json   the JSON mapper
     * @param schema the service schema
     * @throws IllegalArgumentException when the schema is not a plain SQL identifier
     */
    public QuarantineRecordRepository(JdbcTemplate jdbc, ObjectMapper json,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_logingest}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for quarantine_record: " + schema);
        }
        this.jdbc = jdbc;
        this.json = json;
        String table = schema + ".quarantine_record";
        if (!QUALIFIED_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table identifier for quarantine_record: " + table);
        }
        this.upsertSql = "INSERT INTO " + table + " (quarantine_id, binding_id, source_provider, source_kind, "
                + "envelope, detected_version, pinned_version, parser_version, reason_code, reason_detail, raw_ref, "
                + "raw_sha256, raw_size_bytes, raw_excerpt, raw_truncated, event_time, native_event_id, "
                + "pass_through_event_id, quarantined_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (quarantine_id) DO UPDATE SET "
                + "parser_version = EXCLUDED.parser_version, reason_code = EXCLUDED.reason_code, "
                + "reason_detail = EXCLUDED.reason_detail, detected_version = EXCLUDED.detected_version, "
                + "pinned_version = EXCLUDED.pinned_version, event_time = EXCLUDED.event_time, "
                + "native_event_id = EXCLUDED.native_event_id, "
                + "pass_through_event_id = EXCLUDED.pass_through_event_id";
        this.selectHead = "SELECT " + COLUMNS + " FROM " + table;
        this.countHead = "SELECT count(*) FROM " + table;
        this.findByIdSql = selectHead + " WHERE quarantine_id = ?";
        this.markReplaySql = "UPDATE " + table + " SET replay_count = replay_count + 1, "
                + "replayed_at = CASE WHEN ? THEN COALESCE(replayed_at, ?) ELSE replayed_at END "
                + "WHERE quarantine_id = ?";
        this.deleteBeforeSql = "DELETE FROM " + table + " WHERE quarantine_id IN (SELECT quarantine_id FROM "
                + table + " WHERE quarantined_at < ? ORDER BY quarantined_at LIMIT ?)";
        this.topReasonsSql = "SELECT reason_code, count(*) AS n FROM " + table
                + " WHERE binding_id = ? AND quarantined_at >= ? GROUP BY reason_code "
                + "ORDER BY n DESC, reason_code LIMIT ?";
        this.samplesSql = selectHead + " WHERE binding_id = ? AND reason_code = ? AND quarantined_at >= ? "
                + "ORDER BY quarantined_at DESC, quarantine_id LIMIT ?";
        this.countsByBindingSql = "SELECT binding_id, count(*) AS n FROM " + table
                + " WHERE quarantined_at >= ? GROUP BY binding_id";
    }

    /**
     * Joins the fixed head of a filtered statement, the WHERE clause of {@link #where} and a constant
     * tail. Every part is a constant fragment or {@code ?} placeholders; no caller value is in the text.
     */
    private static String compose(String head, String where, String tail) {
        return head + where + tail;
    }

    /**
     * Stores a quarantine record, or updates the stored one of the same id. Quarantining the same
     * record twice is one row (the id hashes binding, source location and raw bytes); the later
     * parser's verdict replaces reason, detail and versions, while {@code quarantined_at} (the
     * retention clock) and the replay marks keep their first values.
     *
     * @param r           the record, already bounded by {@link QuarantineBounds#bounded}
     * @param quarantined when this instance quarantined it
     */
    public void upsert(LogQuarantineRecord r, Instant quarantined) {
        OffsetDateTime at = OffsetDateTime.ofInstant(quarantined, ZoneOffset.UTC);
        OffsetDateTime eventTime = r.hasEventTime() ? OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(r.getEventTime().getSeconds(), r.getEventTime().getNanos()), ZoneOffset.UTC) : null;
        jdbc.update(upsertSql,
                r.getQuarantineId(), r.getBindingId(), r.getSourceProvider().name(), r.getSourceKind(),
                r.getEnvelope(), r.getDetectedVersion(), r.getPinnedVersion(), r.getParserVersion(),
                QuarantineBounds.reasonCode(r.getReason()), r.getReasonDetail(), rawRefJson(r), r.getRawSha256(),
                r.getRawSizeBytes(), r.getRawExcerpt(), r.getRawTruncated(), eventTime, r.getNativeEventId(),
                r.getPassThroughEventId(), at);
    }

    /**
     * Finds one row.
     *
     * @param quarantineId the id
     * @return the row, or empty
     */
    public Optional<QuarantineRow> findById(String quarantineId) {
        return jdbc.query(findByIdSql, rows, quarantineId)
                .stream().findFirst();
    }

    /**
     * Lists rows, newest first.
     *
     * @param filter the filter
     * @param limit  the page size
     * @param offset rows to skip
     * @return the page
     */
    public List<QuarantineRow> find(Filter filter, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(compose(selectHead, where,
                " ORDER BY quarantined_at DESC, quarantine_id LIMIT ? OFFSET ?"), rows, args.toArray());
    }

    /**
     * Counts rows.
     *
     * @param filter the filter
     * @return how many rows match
     */
    public long count(Filter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        Long n = jdbc.queryForObject(compose(countHead, where, ""), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /**
     * Lists pending rows (not yet resolved by a replay), oldest first, for a replay.
     *
     * @param filter the filter; {@code replayed} is forced to {@code false}
     * @param limit  the most rows
     * @return the rows
     */
    public List<QuarantineRow> pending(Filter filter, int limit) {
        Filter f = new Filter(filter.bindingId(), filter.reason(), filter.detectedVersion(), Boolean.FALSE,
                filter.from(), filter.to(), filter.ids());
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        args.add(limit);
        return jdbc.query(compose(selectHead, where,
                " ORDER BY quarantined_at, quarantine_id LIMIT ?"), rows, args.toArray());
    }

    /**
     * Records a replay attempt on a row.
     *
     * @param quarantineId the id
     * @param resolved     true when the replay produced an event or a skip, so the row is done
     * @param at           the time of the attempt
     * @return true when the row exists
     */
    public boolean markReplayAttempt(String quarantineId, boolean resolved, Instant at) {
        OffsetDateTime t = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        return jdbc.update(markReplaySql, resolved, t, quarantineId) > 0;
    }

    /**
     * Deletes up to a batch of rows quarantined before the cutoff.
     *
     * @param cutoff rows quarantined before this instant go
     * @param batch  the most rows to delete
     * @return how many were deleted
     */
    public int deleteBefore(Instant cutoff, int batch) {
        return jdbc.update(deleteBeforeSql,
                OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC), batch);
    }

    /**
     * The reasons of the pending rows of a binding quarantined since an instant, most frequent first.
     *
     * @param bindingId the binding
     * @param since     the start of the window
     * @param limit     the most reasons
     * @return reasons and counts
     */
    public List<ReasonCount> topReasons(String bindingId, Instant since, int limit) {
        return jdbc.query(topReasonsSql,
                (rs, i) -> new ReasonCount(rs.getString("reason_code"), rs.getLong("n")), bindingId,
                OffsetDateTime.ofInstant(since, ZoneOffset.UTC), limit);
    }

    /**
     * The newest rows of one reason of a binding, as samples for the health page.
     *
     * @param bindingId the binding
     * @param reason    the reason code
     * @param since     the start of the window
     * @param limit     the most rows
     * @return the rows, newest first
     */
    public List<QuarantineRow> samples(String bindingId, String reason, Instant since, int limit) {
        return jdbc.query(samplesSql, rows, bindingId, reason,
                OffsetDateTime.ofInstant(since, ZoneOffset.UTC), limit);
    }

    /**
     * Counts quarantined rows per binding since an instant.
     *
     * @param since the start of the window
     * @return binding id to count
     */
    public Map<String, Long> countsByBinding(Instant since) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(countsByBindingSql,
                rs -> {
                    out.put(rs.getString("binding_id"), rs.getLong("n"));
                }, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        return out;
    }

    private String where(Filter f, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        String glue = " WHERE ";
        if (f.bindingId() != null) {
            sb.append(glue).append("binding_id = ?");
            args.add(f.bindingId());
            glue = " AND ";
        }
        if (f.reason() != null) {
            sb.append(glue).append("reason_code = ?");
            args.add(f.reason());
            glue = " AND ";
        }
        if (f.detectedVersion() != null) {
            sb.append(glue).append("detected_version = ?");
            args.add(f.detectedVersion());
            glue = " AND ";
        }
        if (f.replayed() != null) {
            sb.append(glue).append(f.replayed() ? "replayed_at IS NOT NULL" : "replayed_at IS NULL");
            glue = " AND ";
        }
        if (f.from() != null) {
            sb.append(glue).append("quarantined_at >= ?");
            args.add(OffsetDateTime.ofInstant(f.from(), ZoneOffset.UTC));
            glue = " AND ";
        }
        if (f.to() != null) {
            sb.append(glue).append("quarantined_at < ?");
            args.add(OffsetDateTime.ofInstant(f.to(), ZoneOffset.UTC));
            glue = " AND ";
        }
        if (f.ids() != null && !f.ids().isEmpty()) {
            sb.append(glue).append("quarantine_id IN (")
                    .append(String.join(", ", java.util.Collections.nCopies(f.ids().size(), "?"))).append(")");
            args.addAll(f.ids());
        }
        return sb.toString();
    }

    private String rawRefJson(LogQuarantineRecord r) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("transport", r.getRawRef().getTransport());
        ref.put("uri", r.getRawRef().getUri());
        ref.put("recordIndex", r.getRawRef().getRecordIndex());
        ref.put("cursor", r.getRawRef().getCursor());
        try {
            return json.writeValueAsString(ref);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("raw_ref cannot be serialized", e);
        }
    }

    private QuarantineRow mapRow(ResultSet rs, int i) throws SQLException {
        Map<String, Object> ref;
        try {
            ref = json.readValue(rs.getString("raw_ref"), MAP);
        } catch (JsonProcessingException e) {
            throw new SQLException("raw_ref of " + rs.getString("quarantine_id") + " is not JSON", e);
        }
        return new QuarantineRow(rs.getString("quarantine_id"), rs.getString("binding_id"),
                rs.getString("source_provider"), rs.getString("source_kind"), rs.getString("envelope"),
                rs.getString("detected_version"), rs.getString("pinned_version"), rs.getString("parser_version"),
                rs.getString("reason_code"), rs.getString("reason_detail"), ref, rs.getString("raw_sha256"),
                rs.getLong("raw_size_bytes"), rs.getString("raw_excerpt"), rs.getBoolean("raw_truncated"),
                instant(rs.getObject("event_time", OffsetDateTime.class)), rs.getString("native_event_id"),
                rs.getString("pass_through_event_id"),
                instant(rs.getObject("quarantined_at", OffsetDateTime.class)),
                instant(rs.getObject("replayed_at", OffsetDateTime.class)), rs.getInt("replay_count"));
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
