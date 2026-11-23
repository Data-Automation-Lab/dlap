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

package com.dlap.logingest.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Records which formats, versions and shape fingerprints each binding delivers
 * ({@code dlap_logingest.format_observation}, Flyway V3, design B2). LOG-03 alerts on new rows
 * ("new version observed") and builds the drift report from them.
 */
@Repository
public class FormatObservationRepository {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final JdbcTemplate jdbc;
    private final String table;

    /**
     * Creates the repository.
     *
     * @param jdbc   the service datasource's template
     * @param schema the service schema
     * @throws IllegalArgumentException when the schema is not a plain SQL identifier
     */
    public FormatObservationRepository(JdbcTemplate jdbc,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_logingest}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for format_observation: " + schema);
        }
        this.jdbc = jdbc;
        this.table = schema + ".format_observation";
    }

    /**
     * Adds records to an observation, creating it when it is new.
     *
     * @param bindingId     the binding
     * @param envelope      the envelope
     * @param schemaVersion the detected version
     * @param fingerprint   the shape fingerprint hash, empty when the parser declares no paths
     * @param records       records seen
     * @param seenAt        the service clock's time of the batch
     * @return true when this (binding, envelope, version, fingerprint) was never seen before
     */
    public boolean record(String bindingId, String envelope, String schemaVersion, String fingerprint,
                          long records, Instant seenAt) {
        OffsetDateTime at = OffsetDateTime.ofInstant(seenAt, ZoneOffset.UTC);
        Boolean inserted = jdbc.queryForObject("INSERT INTO " + table
                        + " (binding_id, envelope, schema_version, fingerprint, first_seen, last_seen, record_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (binding_id, envelope, schema_version, fingerprint) DO UPDATE SET"
                        + " last_seen = GREATEST(" + table + ".last_seen, EXCLUDED.last_seen),"
                        + " record_count = " + table + ".record_count + EXCLUDED.record_count"
                        + " RETURNING (xmax = 0)",
                Boolean.class, bindingId, envelope, schemaVersion, fingerprint == null ? "" : fingerprint, at, at,
                records);
        return Boolean.TRUE.equals(inserted);
    }
}
