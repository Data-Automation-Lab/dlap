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

package com.dlap.catalog.coverage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.catalog.lifecycle.AppliedCoverageObservation;
import com.dlap.catalog.retention.BindingStatusStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Applies read-coverage observations to {@code dlap_common.dlap_assets} (COV-01). The only writer
 * of the {@code read_coverage*} columns.
 *
 * <p>An observation names a root URI and the service types it covers; it is applied to the asset
 * with that URI and to every asset whose URI starts with the root followed by {@code /} (a
 * bucket and its objects, a BigQuery project's datasets and tables, a storage account's
 * containers). The stored value is {@link ReadCoverageCombiner#combine} of the observation and
 * the current binding statuses.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Monotonic.</b> A row whose stored observation is newer is left alone, so redelivery and
 *       reordering never move coverage backwards. An equal time re-applies the same value.</li>
 *   <li><b>Coverage start.</b> {@code read_coverage_since} is the first observation of the current
 *       unbroken run of {@code FULL}: a {@code FULL} after {@code FULL} keeps it, a {@code FULL}
 *       after anything else starts it at the observation time, any other level clears it.</li>
 *   <li><b>No invention.</b> An observation for assets the catalog does not hold changes nothing
 *       and is counted; a newly discovered asset stays unknown until the next observation.</li>
 * </ul>
 */
@Component
public class ReadCoverageApplier {

    private static final Logger log = LoggerFactory.getLogger(ReadCoverageApplier.class);

    /** Observations applied. Tags: {@code outcome} (applied, stale, unmatched) and {@code level}. */
    public static final String METER_OBSERVATIONS = "dlap.catalog.coverage.observations";

    /** What happened to one observation. */
    public enum Outcome {
        /** At least one row was written. */
        APPLIED,
        /** Matching rows exist, but each holds a newer observation. */
        STALE,
        /** No row matches the root and the service types. */
        UNMATCHED
    }

    /**
     * The most service types one observation may name. The wire allows at most three (Azure); the
     * statements are built for every count up to this bound so that no text is formatted when a
     * statement runs.
     */
    static final int MAX_KINDS = 16;

    private static final String MATCH = "cloud_provider = ? AND service_type IN (%s) "
            + "AND (asset_uri = ? OR left(asset_uri, ?) = ?)";

    private static final String UPDATE_TEMPLATE = "UPDATE dlap_common.dlap_assets SET "
            + "read_coverage = CAST(? AS varchar), "
            + "read_coverage_reasons = CAST(? AS jsonb), "
            + "read_coverage_since = CASE WHEN CAST(? AS boolean) "
            + "THEN COALESCE(CASE WHEN read_coverage = 'FULL' THEN read_coverage_since END, CAST(? AS timestamptz)) "
            + "ELSE NULL END, "
            + "read_coverage_observed_at = CAST(? AS timestamptz) "
            + "WHERE " + MATCH + " "
            + "AND (read_coverage_observed_at IS NULL OR read_coverage_observed_at <= CAST(? AS timestamptz))";

    private static final String COUNT_TEMPLATE =
            "SELECT count(*) FROM dlap_common.dlap_assets WHERE " + MATCH;

    /** The update statement for 1 to {@link #MAX_KINDS} service types, built once at class load. */
    private static final List<String> UPDATE_SQL = statements(UPDATE_TEMPLATE);

    /** The count statement for 1 to {@link #MAX_KINDS} service types, built once at class load. */
    private static final List<String> COUNT_SQL = statements(COUNT_TEMPLATE);

    private final JdbcTemplate jdbc;
    private final BindingStatusStore bindings;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final ApplicationEventPublisher events;

    /**
     * Creates the applier.
     *
     * @param jdbc     the catalog datasource
     * @param bindings the stored binding statuses
     * @param mapper   writes the reasons array
     * @param meters   the meter registry
     * @param events   publishes {@link AppliedCoverageObservation} for the Lifecycle State evidence
     */
    public ReadCoverageApplier(JdbcTemplate jdbc, BindingStatusStore bindings, ObjectMapper mapper,
                               MeterRegistry meters, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.bindings = bindings;
        this.mapper = mapper;
        this.meters = meters;
        this.events = events;
    }

    /**
     * Applies one observation inside the caller's transaction.
     *
     * @param observation the observation
     * @return what happened
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome apply(ReadCoverageObservation observation) {
        ReadCoverageCombiner.Result result = ReadCoverageCombiner.combine(observation, bindings.findAll());
        String level = result.level() == null ? null : result.level().name();
        String reasons;
        try {
            reasons = mapper.writeValueAsString(result.reasons());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write read-coverage reasons", e);
        }
        OffsetDateTime at = utc(observation.observedAt());
        List<String> kinds = new ArrayList<>(observation.appliesTo());
        Collections.sort(kinds);
        String updateSql = statement(UPDATE_SQL, kinds.size());

        List<Object> args = new ArrayList<>();
        args.add(level);
        args.add(reasons);
        args.add(result.level() == ReadCoverageLevel.FULL);
        args.add(at);
        args.add(at);
        addMatch(args, observation, kinds);
        args.add(at);
        int rows = jdbc.update(updateSql, args.toArray());

        Outcome outcome;
        if (rows > 0) {
            outcome = Outcome.APPLIED;
            // LSE-01: the readCoverage provenance of these rows names this observation.
            events.publishEvent(new AppliedCoverageObservation(observation));
        } else {
            List<Object> countArgs = new ArrayList<>();
            addMatch(countArgs, observation, kinds);
            Long matching = jdbc.queryForObject(statement(COUNT_SQL, kinds.size()), Long.class,
                    countArgs.toArray());
            outcome = matching != null && matching > 0 ? Outcome.STALE : Outcome.UNMATCHED;
        }
        meters.counter(METER_OBSERVATIONS, "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT),
                "level", level == null ? ReadCoverageLevel.UNKNOWN : level).increment();
        log.debug("Read coverage {} for {} ({} rows): {}", level == null ? ReadCoverageLevel.UNKNOWN : level,
                observation.rootUri(), rows, outcome);
        return outcome;
    }

    private static List<String> statements(String template) {
        List<String> out = new ArrayList<>(MAX_KINDS);
        for (int kinds = 1; kinds <= MAX_KINDS; kinds++) {
            out.add(template.formatted(String.join(", ", Collections.nCopies(kinds, "?"))));
        }
        return List.copyOf(out);
    }

    /**
     * The prebuilt statement for a number of service types.
     *
     * @param statements the statements, index 0 for one service type
     * @param kinds      the number of service types, 1 to {@link #MAX_KINDS}
     * @return the statement
     * @throws IllegalArgumentException when the count is out of range
     */
    static String statement(List<String> statements, int kinds) {
        if (kinds < 1 || kinds > MAX_KINDS) {
            throw new IllegalArgumentException(
                    "An observation names 1 to " + MAX_KINDS + " service types, not " + kinds);
        }
        return statements.get(kinds - 1);
    }

    /**
     * The update statement for a number of service types (package-private for tests).
     *
     * @param kinds the number of service types
     * @return the statement
     */
    static String updateStatement(int kinds) {
        return statement(UPDATE_SQL, kinds);
    }

    /**
     * The count statement for a number of service types (package-private for tests).
     *
     * @param kinds the number of service types
     * @return the statement
     */
    static String countStatement(int kinds) {
        return statement(COUNT_SQL, kinds);
    }

    private static void addMatch(List<Object> args, ReadCoverageObservation observation, List<String> kinds) {
        args.add(observation.provider());
        args.addAll(kinds);
        String prefix = observation.rootUri() + "/";
        args.add(observation.rootUri());
        args.add(prefix.length());
        args.add(prefix);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
