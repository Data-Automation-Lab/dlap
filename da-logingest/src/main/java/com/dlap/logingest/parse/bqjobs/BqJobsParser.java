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

package com.dlap.logingest.parse.bqjobs;

import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.SkipReason;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResourceRelation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses one row of {@code INFORMATION_SCHEMA.JOBS_BY_PROJECT} into read and write events.
 *
 * <p>The mapping YAML ({@code dlap/log-formats/bqjobs/mapping.yaml}) carries what a mapping can say:
 * detection, native key, event time, operation, principal, failure, the rows that are no event
 * (background jobs, running jobs, script parents, cache hits, extract jobs) and the category of each
 * statement type. This class adds what needs code: the fan-out of one row into one event per table.
 *
 * <p>What a finished job did, for a row that is not skipped:
 * <ul>
 *   <li><b>Reads.</b> A {@code QUERY} job whose statement type is not on {@link #NO_READ_STATEMENTS}
 *       reads every table in {@code referenced_tables} and every view in
 *       {@code referenced_logical_views}, except the table it writes. Each read is a {@code DATA_READ}
 *       event with change type {@code ACCESS}. The list is the one the zero-access scan
 *       ({@code scripts/scan/bigquery/last_read.sql}, SCAN-01) uses, so both count the same reads.</li>
 *   <li><b>Write.</b> The job's {@code destination_table} is the table it wrote, unless it is a hidden
 *       anonymous result table. DML, {@code CREATE TABLE AS SELECT}, {@code LOAD DATA} and load and
 *       copy jobs are {@code DATA_WRITE}; table DDL is {@code MANAGEMENT}; a query with an explicit
 *       destination is a {@code DATA_WRITE}.</li>
 *   <li><b>Never.</b> A cache hit reads nothing, a script parent has no reads of its own, and the query
 *       text is neither kept nor looked at.</li>
 * </ul>
 *
 * <p>Identity: the row's native key is {@code GCP_BQ_JOBS_VIEW|project|location|job_id}. The write
 * event, or without a write the first read in canonical order, is the primary event and keeps it; the
 * others append {@code #<canonical table id>} ({@link LogEventIds#fanOutNativeKey}). Every event
 * therefore has one id per job and table, and a row seen on two polls produces the same ids.
 */
public final class BqJobsParser extends MappingDrivenParser {

    /**
     * Statement types whose referenced tables are not reads: definitions and drops of objects, not
     * data access. This is the exclusion list of {@code scripts/scan/bigquery/last_read.sql}; keep
     * the two in step ({@code BqJobsScanOracleTest} compares them on the same rows).
     */
    static final Set<String> NO_READ_STATEMENTS = Set.of(
            "SCRIPT",
            "CREATE_VIEW", "ALTER_VIEW", "DROP_VIEW",
            "ALTER_MATERIALIZED_VIEW", "DROP_MATERIALIZED_VIEW",
            "ALTER_TABLE", "DROP_TABLE", "DROP_EXTERNAL_TABLE", "TRUNCATE_TABLE",
            "DROP_SNAPSHOT_TABLE", "CREATE_EXTERNAL_TABLE",
            "CREATE_SCHEMA", "ALTER_SCHEMA", "DROP_SCHEMA",
            "CREATE_FUNCTION", "CREATE_TABLE_FUNCTION", "DROP_FUNCTION", "DROP_TABLE_FUNCTION",
            "CREATE_PROCEDURE", "DROP_PROCEDURE",
            "CREATE_ROW_ACCESS_POLICY", "DROP_ROW_ACCESS_POLICY", "DROP_ALL_ROW_ACCESS_POLICIES",
            "DROP_MODEL", "DROP_SEARCH_INDEX", "DROP_VECTOR_INDEX");

    /**
     * Principals that are Google-operated service agents and the Cloud Services account. Their reads
     * are typed {@code SYSTEM}, as in the scan (its {@code system_principal_regex} default).
     */
    private static final Pattern SYSTEM_PRINCIPAL = Pattern.compile(
            "(@gcp-sa-[a-z0-9-]+\\.iam\\.gserviceaccount\\.com|@cloudservices\\.gserviceaccount\\.com)$");

    /**
     * The provider documents that a query referencing more than 50 tables has no complete list
     * ({@code JobStatistics2.referencedTables}), so a list this long may be cut.
     */
    static final int PROVIDER_REFERENCE_LIMIT = 50;

    /** Related sources listed on a write event; the full list is on the read events. */
    private static final int MAX_RELATED_SOURCES = 16;

    /** Most tables one row may fan out to; a defence against a malformed row, never reached in practice. */
    private static final int MAX_TARGETS = 512;

    /**
     * Creates the parser on its mapping.
     *
     * @param spec the {@code bqjobs} family mapping
     */
    public BqJobsParser(MappingSpec spec) {
        super(spec, BqJobsFormat.ENVELOPE, BqJobsFormat.RANGE, BqJobsFormat.PARSER_ID, BqJobsFormat.PARSER_SEMVER);
    }

    @Override
    public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
        JsonNode body = record.body();
        Optional<ParseResult> invalid = validateShape(body);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        ParseResult base = super.parse(record, effective, binding);
        if (!(base instanceof ParseResult.Parsed parsed)) {
            return base;
        }
        CanonicalEvent template = parsed.events().get(0);
        return expand(body, template, parsed);
    }

    // ------------------------------------------------------------------
    // Shape checks the mapping cannot express
    // ------------------------------------------------------------------

    private static Optional<ParseResult> validateShape(JsonNode body) {
        if (!body.isObject()) {
            return Optional.of(ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "a JOBS row is a JSON object keyed by column name"));
        }
        List<String> missing = BqJobsFormat.REQUIRED_COLUMNS.stream().filter(c -> !body.has(c)).toList();
        if (!missing.isEmpty()) {
            return Optional.of(ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                    "column drift: the row lacks required column(s) " + missing + "; the result set no longer "
                            + "matches column set " + BqJobsFormat.VERSION));
        }
        for (String column : List.of("job_id", "project_id", "state")) {
            JsonNode n = body.get(column);
            if (n == null || !n.isTextual() || n.asText().isBlank()) {
                return Optional.of(ParseResult.Quarantined.of(
                        n == null || n.isNull() ? QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD
                                : QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        column + " must be non-empty text"));
            }
        }
        for (String column : List.of("referenced_tables", "referenced_logical_views")) {
            JsonNode n = body.get(column);
            if (n != null && !n.isNull() && !n.isArray()) {
                return Optional.of(ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        column + " must be an array of tables"));
            }
        }
        JsonNode destination = body.get("destination_table");
        if (destination != null && !destination.isNull() && !destination.isObject()) {
            return Optional.of(ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "destination_table must be a table reference"));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Fan-out
    // ------------------------------------------------------------------

    /** A table or view named by the row. */
    private record Table(String project, String dataset, String table, String canonical) {

        String key() {
            return canonical.isEmpty() ? project + "." + dataset + "." + table : canonical;
        }

        String nativeId() {
            return "projects/" + project + "/datasets/" + dataset + "/tables/" + table;
        }
    }

    private static Table tableOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String project = text(node, "project_id");
        String dataset = text(node, "dataset_id");
        String table = text(node, "table_id");
        if (project.isEmpty() || dataset.isEmpty() || table.isEmpty()) {
            return null;
        }
        String canonical = "";
        AssetIdParseResult built = AssetIdNormalizer.build(AssetKind.BIGQUERY_TABLE, List.of(project, dataset, table));
        if (built instanceof AssetIdParseResult.Normalized n) {
            canonical = n.canonical().uri();
        }
        return new Table(project, dataset, table, canonical);
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || !n.isTextual() ? "" : n.asText();
    }

    private static boolean flag(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.isBoolean() && n.booleanValue();
    }

    private ParseResult expand(JsonNode body, CanonicalEvent template, ParseResult.Parsed parsed) {
        String jobType = text(body, "job_type");
        String statement = text(body, "statement_type");
        boolean heuristic = template.warnings().contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
        EventCategory statementCategory = template.category();

        Table destination = tableOf(body.get("destination_table"));
        Table written = destination != null && !BqJobsFormat.isAnonymousDataset(destination.dataset()) ? destination : null;

        Map<String, Table> referenced = new LinkedHashMap<>();
        collect(body.get("referenced_tables"), referenced);
        int providerListed = body.path("referenced_tables").isArray() ? body.get("referenced_tables").size() : 0;
        Map<String, Table> views = new LinkedHashMap<>();
        collect(body.get("referenced_logical_views"), views);

        // The write target. Table DDL and TRUNCATE name their table in destination_table, or, when the
        // job has no destination, in a single referenced table.
        Table writeTarget = written;
        boolean statementWrites = !heuristic && (statementCategory == EventCategory.EVENT_CATEGORY_MANAGEMENT
                || statementCategory == EventCategory.EVENT_CATEGORY_DATA_WRITE);
        if (writeTarget == null && statementWrites && "QUERY".equals(jobType) && namesItsTargetAsAReference(statement)
                && referenced.size() == 1) {
            writeTarget = referenced.values().iterator().next();
        }

        // The reads: the scan's rule. Tables except the one written, plus views.
        List<Table> reads = new ArrayList<>();
        if ("QUERY".equals(jobType) && !NO_READ_STATEMENTS.contains(statement)) {
            Set<String> seen = new java.util.HashSet<>();
            for (Table t : referenced.values()) {
                if (written != null && written.key().equals(t.key())) {
                    continue;
                }
                if (seen.add(t.key())) {
                    reads.add(t);
                }
            }
            for (Table v : views.values()) {
                if (seen.add(v.key())) {
                    reads.add(v);
                }
            }
        }
        reads.sort(Comparator.comparing(Table::key));
        if (reads.size() > MAX_TARGETS) {
            reads = new ArrayList<>(reads.subList(0, MAX_TARGETS));
        }

        List<Planned> plan = new ArrayList<>();
        if (writeTarget != null) {
            plan.add(Planned.write(writeTarget, statementWrites, statementCategory, template.changeType()));
        }
        for (Table t : reads) {
            plan.add(Planned.read(t));
        }
        if (plan.isEmpty()) {
            return new ParseResult.Skipped(SkipReason.NOT_AN_EVENT, "the job reads and writes no table");
        }

        CanonicalEvent.Actor actor = actor(body);
        CanonicalEvent.Resource job = jobResource(body);
        Set<ParseWarning> extra = new java.util.LinkedHashSet<>();
        if (providerListed >= PROVIDER_REFERENCE_LIMIT) {
            extra.add(ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED);
        }

        List<CanonicalEvent> events = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Planned p = plan.get(i);
            String key = i == 0 ? template.nativeKey() : LogEventIds.fanOutNativeKey(template.nativeKey(), p.table().key());
            CanonicalEvent.Builder b = template.toBuilder()
                    .nativeKey(key)
                    .principal(actor)
                    .changeType(p.changeType())
                    .category(p.category())
                    .target(resource(p.table(), ResourceRelation.RESOURCE_RELATION_UNSPECIFIED))
                    .addRelated(related(job, ResourceRelation.RESOURCE_RELATION_OTHER));
            extra.forEach(b::warning);
            if (p.write()) {
                int listed = 0;
                for (Planned other : plan) {
                    if (!other.write() && listed++ < MAX_RELATED_SOURCES) {
                        b.addRelated(resource(other.table(), ResourceRelation.RESOURCE_RELATION_SOURCE));
                    }
                }
            } else if (writeTarget != null) {
                b.addRelated(resource(writeTarget, ResourceRelation.RESOURCE_RELATION_DESTINATION));
            }
            events.add(b.build());
        }
        return new ParseResult.Parsed(events, parsed.warnings());
    }

    /**
     * Statements whose single referenced table is the object they act on: a drop, an alter, a truncate.
     * A create names its definition's sources there, not what it creates, so it never qualifies.
     */
    private static boolean namesItsTargetAsAReference(String statement) {
        return statement.startsWith("DROP_") || statement.startsWith("ALTER_") || "TRUNCATE_TABLE".equals(statement);
    }

    private static void collect(JsonNode array, Map<String, Table> into) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode n : array) {
            Table t = tableOf(n);
            if (t != null && !BqJobsFormat.isInformationSchema(t.dataset())) {
                into.putIfAbsent(t.key(), t);
            }
        }
    }

    /** One planned event: a table, whether it is the write, and its category and change type. */
    private record Planned(Table table, boolean write, EventCategory category, ChangeType changeType) {

        static Planned read(Table table) {
            return new Planned(table, false, EventCategory.EVENT_CATEGORY_DATA_READ, ChangeType.ACCESS);
        }

        static Planned write(Table table, boolean statementWrites, EventCategory statementCategory, ChangeType statementChange) {
            return statementWrites
                    ? new Planned(table, true, statementCategory, statementChange)
                    : new Planned(table, true, EventCategory.EVENT_CATEGORY_DATA_WRITE, ChangeType.UPDATE);
        }
    }

    private static CanonicalEvent.Resource resource(Table t, ResourceRelation relation) {
        return CanonicalEvent.Resource.builder()
                .kind(BqJobsFormat.TABLE_KIND)
                .id(t.nativeId())
                .name(t.table())
                .project(t.project())
                .resourcePath(t.nativeId())
                .canonicalUri(t.canonical())
                .relation(relation)
                .build();
    }

    private static CanonicalEvent.Resource related(CanonicalEvent.Resource resource, ResourceRelation relation) {
        return CanonicalEvent.Resource.builder()
                .kind(resource.kind()).id(resource.id()).name(resource.name()).project(resource.project())
                .region(resource.region()).resourcePath(resource.resourcePath())
                .canonicalUri(resource.canonicalUri()).relation(relation).build();
    }

    private static CanonicalEvent.Resource jobResource(JsonNode body) {
        String project = text(body, "project_id");
        String jobId = text(body, "job_id");
        String canonical = "";
        AssetIdParseResult built = AssetIdNormalizer.build(AssetKind.BIGQUERY_JOB, List.of(project, jobId));
        if (built instanceof AssetIdParseResult.Normalized n) {
            canonical = n.canonical().uri();
        }
        return CanonicalEvent.Resource.builder()
                .kind(BqJobsFormat.JOB_KIND)
                .id("projects/" + project + "/jobs/" + jobId)
                .name(jobId)
                .project(project)
                .region(text(body, BqJobsFormat.LOCATION_COLUMN))
                .canonicalUri(canonical)
                .build();
    }

    // ------------------------------------------------------------------
    // Principal
    // ------------------------------------------------------------------

    private static CanonicalEvent.Actor actor(JsonNode body) {
        String email = text(body, "user_email");
        String subject = text(body, "principal_subject");
        String id = !email.isBlank() ? email : subject;
        return new CanonicalEvent.Actor(principalType(email, subject), id, "", "");
    }

    /**
     * Types the caller the way the zero-access scan does, so both count the same readers.
     *
     * @param email   the {@code user_email} column, or empty
     * @param subject the {@code principal_subject} column, or empty
     * @return the principal type; unspecified when neither column says
     */
    static PrincipalType principalType(String email, String subject) {
        String key = (!email.isBlank() ? email : subject).toLowerCase(Locale.ROOT);
        if (SYSTEM_PRINCIPAL.matcher(key).find()) {
            return PrincipalType.SYSTEM;
        }
        if (subject.startsWith("serviceAccount:") || email.toLowerCase(Locale.ROOT).endsWith(".gserviceaccount.com")
                || subject.contains("/workloadIdentityPools/")) {
            return PrincipalType.SERVICE_ACCOUNT;
        }
        if (subject.contains("/workforcePools/") || subject.startsWith("user:") || email.contains("@")) {
            return PrincipalType.USER;
        }
        return PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED;
    }

    // ------------------------------------------------------------------
    // Raw event: the row without the columns that must not be stored
    // ------------------------------------------------------------------

    @Override
    protected JsonNode rawEvent(RawRecord record, MappingSpec.Effective mapping) {
        JsonNode body = record.body();
        ObjectNode kept = JsonNodeFactory.instance.objectNode();
        if (!body.isObject()) {
            return kept;
        }
        body.properties().forEach(e -> {
            if (BqJobsFormat.KEPT_COLUMNS.contains(e.getKey())) {
                kept.set(e.getKey(), e.getValue().deepCopy());
            }
        });
        return kept;
    }

    @Override
    public Set<FieldPath> declaredPaths(String effectiveVersion) {
        Set<FieldPath> all = new java.util.LinkedHashSet<>(super.declaredPaths(effectiveVersion));
        all.addAll(knownColumns());
        return Set.copyOf(all);
    }

    /**
     * The declared paths a record may have beyond the mapping's own: every kept, dropped and ignored
     * column, so none of them is reported as an unknown field.
     *
     * @return the pointers, one per column
     */
    static Set<FieldPath> knownColumns() {
        Set<FieldPath> out = new java.util.LinkedHashSet<>();
        for (Set<String> columns : List.of(BqJobsFormat.KEPT_COLUMNS, BqJobsFormat.DROPPED_COLUMNS,
                BqJobsFormat.IGNORED_COLUMNS)) {
            columns.stream().sorted().forEach(c -> out.add(FieldPath.of("/" + FieldPath.escape(c))));
        }
        return out;
    }
}
