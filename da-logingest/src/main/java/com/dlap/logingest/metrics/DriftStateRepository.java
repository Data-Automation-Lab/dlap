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

package com.dlap.logingest.metrics;

import com.dlap.logingest.metrics.ParseTelemetry.DriftKey;
import com.dlap.logingest.metrics.ParseTelemetry.FillBaseline;
import com.dlap.logingest.metrics.ParseTelemetry.UnknownPath;
import com.dlap.logingest.metrics.ParseTelemetry.UnknownPathDelta;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The durable half of the drift signals (Q-364): {@code unknown_path_observation} and
 * {@code fill_baseline}, Flyway V5. Writers add; they never replace, so replicas that flush the
 * same key converge: a count is summed, a baseline is the higher of the two, a timestamp the later.
 * Only JSON paths and field labels are stored, never values.
 */
@Repository
public class DriftStateRepository {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** Widest key column of the V5 tables; a longer value is not stored. */
    static final int MAX_KEY_LENGTH = 128;

    /** Widest path column of the V5 table. */
    static final int MAX_PATH_LENGTH = 200;

    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final String addPathsSql;
    private final String raiseBaselinesSql;
    private final String pathsSinceSql;
    private final String baselinesSinceSql;
    private final String prunePathsSql;
    private final String pruneBaselinesSql;

    /**
     * Creates the repository.
     *
     * @param jdbc   the service datasource's template
     * @param schema the service schema
     * @throws IllegalArgumentException when the schema is not a plain SQL identifier
     */
    public DriftStateRepository(JdbcTemplate jdbc,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_logingest}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the drift state tables: " + schema);
        }
        this.jdbc = jdbc;
        // The only text spliced into a statement is the schema, validated above; the finished
        // statements are built once here and the methods pass the constants to the template.
        String pathTable = schema + ".unknown_path_observation";
        String baselineTable = schema + ".fill_baseline";
        this.addPathsSql = "INSERT INTO " + pathTable
                + " (source_kind, envelope, schema_version, path, observed_count, first_seen, last_seen)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (source_kind, envelope, schema_version, path) DO UPDATE SET"
                + " observed_count = " + pathTable + ".observed_count + EXCLUDED.observed_count,"
                + " last_seen = GREATEST(" + pathTable + ".last_seen, EXCLUDED.last_seen)";
        this.raiseBaselinesSql = "INSERT INTO " + baselineTable
                + " (source_kind, schema_version, field, baseline, updated_at) VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT (source_kind, schema_version, field) DO UPDATE SET"
                + " baseline = GREATEST(" + baselineTable + ".baseline, EXCLUDED.baseline),"
                + " updated_at = GREATEST(" + baselineTable + ".updated_at, EXCLUDED.updated_at)";
        this.pathsSinceSql = "SELECT source_kind, envelope, schema_version, path, observed_count FROM " + pathTable
                + " WHERE last_seen >= ? ORDER BY observed_count DESC, path LIMIT ?";
        this.baselinesSinceSql = "SELECT source_kind, schema_version, field, baseline FROM " + baselineTable
                + " WHERE updated_at >= ? ORDER BY source_kind, schema_version, field LIMIT ?";
        this.prunePathsSql = "DELETE FROM " + pathTable + " WHERE last_seen < ?";
        this.pruneBaselinesSql = "DELETE FROM " + baselineTable + " WHERE updated_at < ?";
    }

    /**
     * Adds unknown-path sightings, creating the row of a path never stored before.
     *
     * @param deltas the sightings since the last flush
     * @param seenAt the time of the flush
     * @return the number of sightings written (those with a key that fits the columns)
     */
    public int addUnknownPaths(List<UnknownPathDelta> deltas, Instant seenAt) {
        List<UnknownPathDelta> storable = deltas.stream().filter(d -> fits(d.key().source())
                && fits(d.key().format()) && fits(d.key().version()) && d.path().length() <= MAX_PATH_LENGTH
                && d.delta() > 0).toList();
        if (storable.isEmpty()) {
            return 0;
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(seenAt, ZoneOffset.UTC);
        jdbc.batchUpdate(addPathsSql, storable, BATCH, (ps, d) -> {
                ps.setString(1, d.key().source());
                ps.setString(2, d.key().format());
                ps.setString(3, d.key().version());
                ps.setString(4, d.path());
                ps.setLong(5, d.delta());
                ps.setObject(6, at);
                ps.setObject(7, at);
            });
        return storable.size();
    }

    /**
     * Raises baselines, creating the row of a field never stored before, and refreshes their time.
     *
     * @param baselines the baselines to store
     * @param seenAt    the time of the flush
     * @return the number of baselines written
     */
    public int raiseBaselines(List<FillBaseline> baselines, Instant seenAt) {
        List<FillBaseline> storable = baselines.stream().filter(b -> fits(b.source()) && fits(b.version())
                && b.field().length() <= 64 && !Double.isNaN(b.baseline())).toList();
        if (storable.isEmpty()) {
            return 0;
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(seenAt, ZoneOffset.UTC);
        jdbc.batchUpdate(raiseBaselinesSql, storable, BATCH, (ps, b) -> {
                ps.setString(1, b.source());
                ps.setString(2, b.version());
                ps.setString(3, b.field());
                ps.setDouble(4, b.baseline());
                ps.setObject(5, at);
            });
        return storable.size();
    }

    /**
     * Reads the stored unknown paths seen since a time, most frequent first.
     *
     * @param since the oldest {@code last_seen} to read
     * @param limit the most rows to read
     * @return the stored paths
     */
    public List<UnknownPath> unknownPathsSince(Instant since, int limit) {
        return jdbc.query(pathsSinceSql,
                (rs, n) -> new UnknownPath(new DriftKey(rs.getString(1), rs.getString(2), rs.getString(3)),
                        rs.getString(4), rs.getLong(5)),
                OffsetDateTime.ofInstant(since, ZoneOffset.UTC), limit);
    }

    /**
     * Reads the stored baselines refreshed since a time.
     *
     * @param since the oldest {@code updated_at} to read
     * @param limit the most rows to read
     * @return the stored baselines
     */
    public List<FillBaseline> baselinesSince(Instant since, int limit) {
        return jdbc.query(baselinesSinceSql,
                (rs, n) -> new FillBaseline(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4)),
                OffsetDateTime.ofInstant(since, ZoneOffset.UTC), limit);
    }

    /**
     * Deletes what has not been seen since the cutoff.
     *
     * @param cutoff rows older than this are removed
     * @return the number of rows removed from both tables
     */
    public int pruneBefore(Instant cutoff) {
        OffsetDateTime at = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        return jdbc.update(prunePathsSql, at) + jdbc.update(pruneBaselinesSql, at);
    }

    private static boolean fits(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH;
    }
}
