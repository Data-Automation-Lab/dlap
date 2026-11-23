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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.catalog.retention.ReadCoverageSource;
import com.dlap.catalog.retention.RetentionAssetStore;
import com.dlap.catalog.retention.RetentionEvaluation;
import com.dlap.catalog.retention.RetentionEvaluator;
import com.dlap.catalog.retention.RetentionRule;
import com.dlap.catalog.retention.RetentionRuleStore;
import com.dlap.catalog.retention.RuleSource;
import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Builds Lifecycle State 0.1 objects from the catalog (docs/specs/lifecycle-state.md section 4,
 * ADR-0097): the Asset Descriptor row, a read-only retention evaluation at {@code asOf}, and the
 * stored Log Event ids of {@link LifecycleEvidenceStore}.
 *
 * <p>Rules this class enforces:
 * <ul>
 *   <li><b>asOf</b> is the injected {@link Clock}'s instant, read once per request. Every other time
 *       is copied from the row (R3).</li>
 *   <li><b>No invented provenance.</b> A field's provenance lists only stored event ids whose value
 *       equals the row's value. A value that no stored id proves is emitted as {@code null}
 *       (unknown, R1) and counted in {@value #METER_WITHHELD}; the schema requires provenance for
 *       every known time, size and disposition, and the engine cannot name one.</li>
 *   <li><b>Null stays null.</b> A null column is a null member, never a default.</li>
 *   <li><b>One form.</b> {@link LifecycleStateJson} renders the result; equal inputs give equal bytes (R6).</li>
 * </ul>
 *
 * <p>An asset whose stored URI does not normalize, or whose provider or kind is outside the 0.1
 * enumerations, cannot be described by a valid object; it is reported as unrepresentable, never
 * emitted with a guessed value.
 */
@Component
public class LifecycleStateEmitter {

    /** Values withheld for lack of stored evidence. Tag: {@code field}. */
    public static final String METER_WITHHELD = "dlap.catalog.lifecycle_state.withheld";

    /** Reason: the stored URI is rejected by the asset ID normalizer. */
    public static final String ASSET_URI_NOT_CANONICAL = "ASSET_URI_NOT_CANONICAL";
    /** Reason: the provider or the kind is not one of the 0.1 enumerations. */
    public static final String PLATFORM_NOT_IN_0_1 = "PLATFORM_NOT_IN_0_1";

    /** {@code platform.provider} values of 0.1 (spec section 3.1). */
    static final Set<String> PROVIDERS = Set.of("AWS", "GCP", "AZURE", "OCI", "UNITY", "DATABRICKS", "SNOWFLAKE");

    /** {@code platform.kind} values of 0.1 (asset-id.md section 9). */
    static final Set<String> KINDS = Set.of(
            "S3_BUCKET", "S3_OBJECT", "RDS_INSTANCE", "REDSHIFT_CLUSTER", "DYNAMODB_TABLE",
            "GLUE_DATABASE", "GLUE_TABLE", "REDSHIFT_QUERY",
            "GCS_BUCKET", "GCS_OBJECT", "BIGQUERY_DATASET", "BIGQUERY_TABLE", "BIGQUERY_JOB",
            "CLOUD_SQL_INSTANCE",
            "BLOB_STORAGE", "BLOB_CONTAINER", "BLOB_OBJECT", "SQL_DATABASE", "COSMOS_DB", "DATA_LAKE",
            "UNITY_CATALOG", "UNITY_SCHEMA", "UNITY_TABLE", "UNITY_VOLUME",
            "DATABRICKS_JOB", "SNOWFLAKE_TABLE",
            "SPARK_JOB", "PYTHON_JOB", "DATAFLOW_JOB", "DBT_MODEL");

    /** An evidence channel: {@code <CHANNEL>} or {@code <CHANNEL>:<SOURCE_KIND>}. */
    static final Pattern CHANNEL = Pattern.compile("^[A-Z][A-Z0-9_]*(:[A-Z][A-Z0-9_]*)?$");

    /** Channel of a read-coverage observation: a da-discovery collector, origin crawler. */
    static final String COVERAGE_CHANNEL = "CRAWLER";

    static final int MAX_REASONS = 20;
    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_ASSET_LENGTH = 2048;

    private static final Logger log = LoggerFactory.getLogger(LifecycleStateEmitter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COLUMNS = "asset_id, asset_uri, cloud_provider, service_type, status, "
            + "cloud_created_at, last_accessed_at, last_modified_at, read_coverage, "
            + "CAST(read_coverage_reasons AS text) AS read_coverage_reasons, read_coverage_since, "
            + "read_coverage_observed_at, size_bytes, size_observed_at, lifecycle_event_time";

    private static final String ONE_SQL = "SELECT " + COLUMNS + " FROM dlap_common.dlap_assets WHERE asset_id = ?";

    private static final String PAGE_SQL = "SELECT " + COLUMNS + " FROM dlap_common.dlap_assets "
            + "ORDER BY asset_id LIMIT ? OFFSET ?";

    private static final String AFTER_SQL = "SELECT " + COLUMNS + " FROM dlap_common.dlap_assets "
            + "WHERE asset_id > ? ORDER BY asset_id LIMIT ?";

    private static final String COUNT_SQL = "SELECT count(*) FROM dlap_common.dlap_assets";

    private static final RowMapper<Row> ROW = LifecycleStateEmitter::row;

    /**
     * The columns of one asset the object is built from.
     *
     * @param assetId          the asset
     * @param assetUri         the stored URI
     * @param provider         {@code cloud_provider}
     * @param kind             {@code service_type}
     * @param status           the lifecycle status
     * @param createdAt        {@code cloud_created_at}
     * @param lastReadAt       {@code last_accessed_at}
     * @param lastWrittenAt    {@code last_modified_at}
     * @param readCoverage     {@code read_coverage}
     * @param reasonsJson      {@code read_coverage_reasons} as JSON text
     * @param coverageSince    {@code read_coverage_since}
     * @param coverageObserved {@code read_coverage_observed_at}
     * @param sizeBytes        {@code size_bytes}
     * @param sizeObservedAt   {@code size_observed_at}
     * @param lifecycleTime    {@code lifecycle_event_time}
     */
    record Row(UUID assetId, String assetUri, String provider, String kind, String status, Instant createdAt,
               Instant lastReadAt, Instant lastWrittenAt, String readCoverage, String reasonsJson,
               Instant coverageSince, Instant coverageObserved, Long sizeBytes, Instant sizeObservedAt,
               Instant lifecycleTime) {
    }

    /**
     * The outcome for one asset: an object, or the reason none can be emitted.
     *
     * @param assetId         the asset
     * @param state           the object, or {@code null}
     * @param unrepresentable the reason, or {@code null}
     */
    public record Outcome(UUID assetId, LifecycleState state, String unrepresentable) {
    }

    /**
     * One page of objects, all evaluated at the same instant.
     *
     * @param asOf          the evaluation instant of every object
     * @param page          the page number, from 0
     * @param size          the page size
     * @param totalElements the number of assets in the catalog
     * @param outcomes      the outcomes, in {@code asset_id} order
     */
    public record Page(Instant asOf, int page, int size, long totalElements, List<Outcome> outcomes) {
    }

    private final JdbcTemplate jdbc;
    private final RetentionAssetStore retentionAssets;
    private final RetentionRuleStore rules;
    private final RetentionEvaluator evaluator;
    private final ReadCoverageSource coverage;
    private final LifecycleEvidenceStore evidence;
    private final MeterRegistry meters;
    private final Clock clock;

    /**
     * Creates the emitter.
     *
     * @param jdbc            the catalog data source
     * @param retentionAssets the retention view of the asset rows
     * @param rules           the stored retention rules
     * @param evaluator       the read-only retention evaluation
     * @param coverage        the read signal of the retention evaluation
     * @param evidence        the stored Log Event ids
     * @param meters          the meter registry
     * @param clock           the source of {@code asOf}
     */
    public LifecycleStateEmitter(JdbcTemplate jdbc, RetentionAssetStore retentionAssets, RetentionRuleStore rules,
                                 RetentionEvaluator evaluator, ReadCoverageSource coverage,
                                 LifecycleEvidenceStore evidence, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.retentionAssets = retentionAssets;
        this.rules = rules;
        this.evaluator = evaluator;
        this.coverage = coverage;
        this.evidence = evidence;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * The current instant of the injected clock: the {@code asOf} of a request.
     *
     * @return the instant
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * The object of one asset at the clock's current instant.
     *
     * @param assetId the asset
     * @return the outcome, or empty when the asset does not exist
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Outcome> emit(UUID assetId) {
        Instant asOf = clock.instant();
        List<Row> rows = jdbc.query(ONE_SQL, ROW, assetId);
        return build(rows, asOf).stream().findFirst();
    }

    /**
     * One page of objects, in {@code asset_id} order, every asset included whatever its status.
     *
     * @param page the page number, from 0
     * @param size the page size, 1 to {@value LifecycleStateProperties#MAX_PAGE_SIZE}
     * @param asOf the evaluation instant of every object of the page
     * @return the page
     * @throws IllegalArgumentException when {@code page} or {@code size} is out of range
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(int page, int size, Instant asOf) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or more");
        }
        if (size < 1 || size > LifecycleStateProperties.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be 1 to " + LifecycleStateProperties.MAX_PAGE_SIZE);
        }
        Long total = jdbc.queryForObject(COUNT_SQL, Long.class);
        List<Row> rows = jdbc.query(PAGE_SQL, ROW, size, (long) page * size);
        return new Page(asOf, page, size, total == null ? 0 : total, build(rows, asOf));
    }

    /**
     * The objects of the next assets after one id, for a walk over the whole catalog (keyset paging:
     * an asset inserted or changed during the walk is neither skipped nor repeated).
     *
     * @param after the last asset id of the previous call, or the all-zero UUID to start
     * @param limit how many assets, 1 to {@value LifecycleStateProperties#MAX_PAGE_SIZE}
     * @param asOf  the evaluation instant of every object of the walk
     * @return the outcomes, in {@code asset_id} order; empty at the end
     * @throws IllegalArgumentException when {@code limit} is out of range
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Outcome> after(UUID after, int limit, Instant asOf) {
        if (limit < 1 || limit > LifecycleStateProperties.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be 1 to " + LifecycleStateProperties.MAX_PAGE_SIZE);
        }
        return build(jdbc.query(AFTER_SQL, ROW, after, limit), asOf);
    }

    private List<Outcome> build(List<Row> rows, Instant asOf) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Row::assetId).toList();
        Map<UUID, Set<String>> labels = retentionAssets.labelNames(ids);
        Map<UUID, List<RetentionRule>> stored = rules.findByAssets(ids);
        Map<UUID, List<LifecycleEvidenceStore.Evidence>> byAsset = evidence.findByAssets(ids);
        ReadCoverageSource.View view = coverage.snapshot();
        List<Outcome> out = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Optional<RetentionAssetStore.AssetRow> retentionRow = retentionAssets.findById(row.assetId());
            if (retentionRow.isEmpty()) {
                continue;
            }
            RetentionEvaluation eval = evaluator.evaluate(retentionRow.get(),
                    labels.getOrDefault(row.assetId(), Set.of()), stored.getOrDefault(row.assetId(), List.of()),
                    view, asOf);
            out.add(build(row, retentionRow.get().metadata(), eval,
                    byAsset.getOrDefault(row.assetId(), List.of()), asOf));
        }
        return out;
    }

    /**
     * Builds the object of one asset from loaded data; touches no database except the coverage
     * evidence lookup.
     */
    private Outcome build(Row row, RetentionAssetStore.TechnicalKeys keys, RetentionEvaluation eval,
                          List<LifecycleEvidenceStore.Evidence> rows, Instant asOf) {
        if (!PROVIDERS.contains(row.provider()) || !KINDS.contains(row.kind())) {
            return new Outcome(row.assetId(), null, PLATFORM_NOT_IN_0_1);
        }
        Optional<String> asset = canonical(row);
        if (asset.isEmpty()) {
            return new Outcome(row.assetId(), null, ASSET_URI_NOT_CANONICAL);
        }
        List<LifecycleState.ProvenanceEntry> provenance = new ArrayList<>();

        Instant createdAt = proven(row.createdAt(), LifecycleEvidenceRecorder.CREATED_AT, rows, provenance);
        Instant lastReadAt = proven(row.lastReadAt(), LifecycleEvidenceRecorder.LAST_READ_AT, rows, provenance);
        Instant lastWrittenAt = proven(row.lastWrittenAt(), LifecycleEvidenceRecorder.LAST_WRITTEN_AT, rows,
                provenance);

        // readCoverage: the observation behind read_coverage_observed_at.
        String level = level(row.readCoverage());
        List<String> reasons = reasons(row);
        Instant since = row.coverageSince();
        List<String> observations = observations(row);
        if (level != null && (observations.isEmpty() || ("FULL".equals(level) != (since != null)))) {
            withheld("readCoverage");
            level = null;
            reasons = List.of();
            since = null;
            observations = List.of();
        }
        if (!"FULL".equals(level)) {
            since = null;
        }
        if (!observations.isEmpty()) {
            provenance.add(new LifecycleState.ProvenanceEntry("readCoverage", COVERAGE_CHANNEL, List.of(),
                    observations));
        }

        Long size = null;
        Instant sizeAt = null;
        if (row.sizeBytes() != null && row.sizeObservedAt() != null) {
            List<LifecycleEvidenceStore.Evidence> e = matching(rows, LifecycleEvidenceRecorder.SIZE_BYTES,
                    LifecycleEvidenceRecorder.sizeKey(row.sizeBytes(), row.sizeObservedAt()));
            if (e.isEmpty()) {
                withheld(LifecycleEvidenceRecorder.SIZE_BYTES);
            } else {
                size = row.sizeBytes();
                sizeAt = row.sizeObservedAt();
                provenance.add(entry(LifecycleEvidenceRecorder.SIZE_BYTES, e));
            }
        } else if (row.sizeBytes() != null || row.sizeObservedAt() != null) {
            withheld(LifecycleEvidenceRecorder.SIZE_BYTES);
        }

        LifecycleState.Retention retention = retention(eval);
        providerRuleEvidence(eval, keys, rows).ifPresent(e -> provenance.add(entry("retention", e)));
        boolean legalHold = eval.status() == RetentionStatus.ON_HOLD;

        Disposed disposed = disposition(row, rows, legalHold);
        LifecycleState.Disposition disposition = disposed == null ? null : disposed.disposition();
        if (disposed != null) {
            provenance.add(entry("disposition", disposed.evidence()));
        }

        LifecycleState state = new LifecycleState(LifecycleState.SPEC_VERSION, asset.get(),
                new LifecycleState.Platform(row.provider(), row.kind()), asOf, createdAt, lastReadAt, lastWrittenAt,
                level, reasons, since, size, sizeAt, retention, legalHold, disposition, provenance);
        return new Outcome(row.assetId(), state, null);
    }

    /** The stored URI normalized (asset-id.md, R8), when it is a canonical URI of at most 2048 characters. */
    private static Optional<String> canonical(Row row) {
        AssetIdHints.Builder hints = AssetIdHints.builder();
        AssetProvider.fromString(row.provider()).ifPresent(hints::provider);
        AssetKind.fromServiceType(row.kind()).ifPresent(hints::kind);
        return AssetIdNormalizer.normalize(row.assetUri(), hints.build()).map(CanonicalAssetId::uri)
                .filter(uri -> uri.length() <= MAX_ASSET_LENGTH);
    }

    /** A time the stored ids prove, with its provenance entry; otherwise {@code null}. */
    private Instant proven(Instant value, String field, List<LifecycleEvidenceStore.Evidence> rows,
                           List<LifecycleState.ProvenanceEntry> provenance) {
        if (value == null) {
            return null;
        }
        List<LifecycleEvidenceStore.Evidence> e = matching(rows, field, LifecycleEvidenceRecorder.timeKey(value));
        if (e.isEmpty()) {
            withheld(field);
            return null;
        }
        provenance.add(entry(field, e));
        return value;
    }

    private static String level(String stored) {
        if (stored == null) {
            return null;
        }
        String level = stored.trim().toUpperCase(Locale.ROOT);
        return Set.of("FULL", "PARTIAL", "NONE").contains(level) ? level : null;
    }

    /** The stored reasons in observation order, at most 20 of 500 characters, as the applier keeps them. */
    private static List<String> reasons(Row row) {
        if (row.reasonsJson() == null || row.reasonsJson().isBlank()) {
            return List.of();
        }
        List<String> stored;
        try {
            stored = JSON.readValue(row.reasonsJson(), new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            log.warn("Asset {} has unreadable read_coverage_reasons; emitting none", row.assetId());
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String reason : stored == null ? List.<String>of() : stored) {
            if (reason == null || reason.isEmpty()) {
                continue;
            }
            out.add(reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason);
            if (out.size() == MAX_REASONS) {
                break;
            }
        }
        return out;
    }

    /** The ids of the applied observations behind the row's coverage, ascending. */
    private List<String> observations(Row row) {
        if (row.coverageObserved() == null) {
            return List.of();
        }
        TreeSet<String> ids = new TreeSet<>();
        for (LifecycleEvidenceStore.CoverageEvidence c : evidence.coverageAt(row.provider(), row.coverageObserved())) {
            if (c.appliesTo(row.assetUri(), row.provider(), row.kind())) {
                ids.add(c.eventId());
            }
        }
        return List.copyOf(ids);
    }

    private static LifecycleState.Retention retention(RetentionEvaluation eval) {
        RetentionRule rule = eval.decidingRule();
        if (rule == null) {
            return new LifecycleState.Retention(eval.status().name(), null, null, null, eval.expiresAt(), null);
        }
        Instant fixedDate = rule.basis() == RetentionBasis.FIXED_DATE ? rule.fixedExpiry() : null;
        return new LifecycleState.Retention(eval.status().name(), rule.basis().name(), rule.days(), fixedDate,
                eval.expiresAt(), rule.source().name());
    }

    /** The crawl events that carried a deciding PROVIDER rule, when they are stored. */
    private static Optional<List<LifecycleEvidenceStore.Evidence>> providerRuleEvidence(
            RetentionEvaluation eval, RetentionAssetStore.TechnicalKeys keys, List<LifecycleEvidenceStore.Evidence> rows) {
        RetentionRule rule = eval.decidingRule();
        if (rule == null || rule.source() != RuleSource.PROVIDER || keys == null) {
            return Optional.empty();
        }
        boolean fixed = rule.basis() == RetentionBasis.FIXED_DATE;
        String key = fixed ? LifecycleEvidenceRecorder.EXPIRATION_TIME : LifecycleEvidenceRecorder.RETENTION_PERIOD_DAYS;
        String value = fixed ? keys.expirationTime() : keys.retentionPeriodDays();
        if (value == null) {
            return Optional.empty();
        }
        List<LifecycleEvidenceStore.Evidence> e = matching(rows, LifecycleEvidenceRecorder.RETENTION_PREFIX + key,
                value);
        return e.isEmpty() ? Optional.empty() : Optional.of(e);
    }

    /**
     * The disposition of an ARCHIVED or DELETED row whose transition event is stored and names its
     * executor; {@code null} otherwise (spec section 3.7: no evidence, another actor, or a delete on a
     * held asset, which 0.1 cannot represent).
     */
    private Disposed disposition(Row row, List<LifecycleEvidenceStore.Evidence> rows, boolean legalHold) {
        String action;
        if (DlapConstants.AssetStatus.ARCHIVED.equals(row.status())) {
            action = "archive";
        } else if (DlapConstants.AssetStatus.DELETED.equals(row.status())) {
            action = "delete";
        } else {
            return null;
        }
        if (row.lifecycleTime() == null) {
            withheld(LifecycleEvidenceRecorder.DISPOSITION);
            return null;
        }
        List<LifecycleEvidenceStore.Evidence> e = matching(rows, LifecycleEvidenceRecorder.DISPOSITION,
                LifecycleEvidenceRecorder.dispositionKey(action, row.lifecycleTime()));
        Set<String> actors = new TreeSet<>();
        e.forEach(x -> actors.add(x.executedBy() == null ? LifecycleEvidenceRecorder.OTHER : x.executedBy()));
        if (e.isEmpty() || actors.size() != 1 || actors.contains(LifecycleEvidenceRecorder.OTHER)) {
            withheld(LifecycleEvidenceRecorder.DISPOSITION);
            return null;
        }
        if (legalHold && "delete".equals(action)) {
            log.warn("Asset {} is DELETED and held; Lifecycle State 0.1 cannot represent a delete on a held asset, "
                    + "so its disposition is emitted as null", row.assetId());
            withheld(LifecycleEvidenceRecorder.DISPOSITION);
            return null;
        }
        LifecycleState.Disposition d = new LifecycleState.Disposition(action, row.lifecycleTime(),
                actors.iterator().next(), e.stream().map(LifecycleEvidenceStore.Evidence::eventId).distinct().sorted()
                .toList());
        return new Disposed(d, e);
    }

    /** A disposition and the evidence rows behind it. */
    private record Disposed(LifecycleState.Disposition disposition, List<LifecycleEvidenceStore.Evidence> evidence) {
    }

    private static List<LifecycleEvidenceStore.Evidence> matching(List<LifecycleEvidenceStore.Evidence> rows,
                                                                  String field, String valueKey) {
        Predicate<LifecycleEvidenceStore.Evidence> match = e -> e.field().equals(field) && e.valueKey().equals(valueKey)
                && LifecycleEvidenceStore.EVENT_ID.matcher(e.eventId()).matches();
        return rows.stream().filter(match).toList();
    }

    /**
     * A provenance entry: ids and binding ids ascending without duplicates; the channel of the first
     * id, or {@code null} when it is not a channel of the 0.1 form.
     */
    static LifecycleState.ProvenanceEntry entry(String field, Collection<LifecycleEvidenceStore.Evidence> rows) {
        List<LifecycleEvidenceStore.Evidence> sorted = rows.stream()
                .sorted((a, b) -> a.eventId().compareTo(b.eventId())).toList();
        List<String> ids = sorted.stream().map(LifecycleEvidenceStore.Evidence::eventId).distinct().toList();
        TreeSet<String> bindings = new TreeSet<>();
        sorted.forEach(e -> {
            if (e.bindingId() != null && !e.bindingId().isEmpty()) {
                bindings.add(e.bindingId());
            }
        });
        String channel = sorted.get(0).channel();
        if (channel != null && (channel.length() > 64 || !CHANNEL.matcher(channel).matches())) {
            channel = null;
        }
        return new LifecycleState.ProvenanceEntry(field, channel, List.copyOf(bindings), ids);
    }

    private void withheld(String field) {
        meters.counter(METER_WITHHELD, "field", field).increment();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("asset_id", UUID.class), rs.getString("asset_uri"), rs.getString("cloud_provider"),
                rs.getString("service_type"), rs.getString("status"), instant(rs, "cloud_created_at"),
                instant(rs, "last_accessed_at"), instant(rs, "last_modified_at"), rs.getString("read_coverage"),
                rs.getString("read_coverage_reasons"), instant(rs, "read_coverage_since"),
                instant(rs, "read_coverage_observed_at"), rs.getObject("size_bytes", Long.class),
                instant(rs, "size_observed_at"), instant(rs, "lifecycle_event_time"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
