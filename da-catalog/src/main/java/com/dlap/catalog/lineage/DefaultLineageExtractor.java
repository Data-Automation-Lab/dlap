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

package com.dlap.catalog.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reference {@link LineageExtractor}. Reads edges in a fixed precedence and
 * returns the first form that yields any:
 *
 * <ol>
 *   <li><b>Canonical (future) fields</b> - top-level {@code sourceResources}
 *       and {@code targetResources} lists (each element a map carrying
 *       {@code id}/{@code uri}). This is the shape da-logingest will emit once
 *       providers surface multi-resource operations directly; it is preferred
 *       whenever present.</li>
 *   <li><b>BigQuery-structured</b> - a query/job event whose {@code rawEvent}
 *       names {@code referencedTables} (reads) and a {@code destinationTable}
 *       (write). Each referenced -> destination pair is one edge. Tolerant of
 *       the common BigQuery audit nesting: both
 *       {@code rawEvent.jobStatistics.*} and {@code rawEvent.*} are searched.</li>
 *   <li><b>SQL-text (best-effort)</b> - a query event that carries only the raw
 *       statement ({@code query}/{@code sql}/{@code queryText}/{@code statement}).
 *       A lightweight regex parser pairs the read tables ({@code FROM}/{@code JOIN}
 *       /{@code USING}) with the single write target ({@code INSERT INTO},
 *       {@code INSERT OVERWRITE}, {@code CREATE TABLE|VIEW ... AS}, {@code MERGE
 *       INTO}, {@code UPDATE}). Edges only form when both a write target and at
 *       least one read source are present, so pure {@code SELECT}s and
 *       source-less DDL/DML yield nothing. Comments and string literals are
 *       stripped first, and {@code WITH ... AS (...)} CTE names are excluded, so
 *       neither commented-out SQL, quoted text, nor query-local aliases produce
 *       phantom edges. This is a heuristic, not a SQL engine: it does not resolve
 *       subquery aliases or column-level lineage, and marks its edges
 *       {@code lineageSource=sql-parse} for provenance.</li>
 *   <li><b>Empty</b> - none of the above matched.</li>
 * </ol>
 *
 * <p>The relationship type for BigQuery edges is derived from the event's
 * {@code operation} / {@code jobType} (defaulting to {@code QUERY}); details
 * carry the operation and, when present, the {@code jobId}.
 */
@Component
public class DefaultLineageExtractor implements LineageExtractor {

    private static final Logger log = LoggerFactory.getLogger(DefaultLineageExtractor.class);

    private static final String DEFAULT_RELATIONSHIP = "QUERY";

    // ---- SQL-text parsing (form c) -----------------------------------------

    /** Case-insensitive, DOTALL so multi-line statements match across newlines. */
    private static final int SQL_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    /**
     * Write-target patterns, tried in order. Each captures the target table token
     * in group 1; the paired relationship label is the map value. A token runs up
     * to the next whitespace, '(', ',', ';' - enough to grab a (possibly quoted /
     * qualified) table name while stopping before a column list or subquery.
     */
    private static final Pattern P_INSERT_OVERWRITE =
            Pattern.compile("\\bINSERT\\s+OVERWRITE\\s+(?:TABLE\\s+)?([^\\s(),;]+)", SQL_FLAGS);
    private static final Pattern P_INSERT_INTO =
            Pattern.compile("\\bINSERT\\s+INTO\\s+([^\\s(),;]+)", SQL_FLAGS);
    private static final Pattern P_CREATE_AS =
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMP(?:ORARY)?\\s+)?"
                    + "(?:MATERIALIZED\\s+)?(?:TABLE|VIEW)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([^\\s(),;]+)", SQL_FLAGS);
    private static final Pattern P_MERGE =
            Pattern.compile("\\bMERGE\\s+INTO\\s+([^\\s(),;]+)", SQL_FLAGS);
    private static final Pattern P_UPDATE =
            Pattern.compile("\\bUPDATE\\s+([^\\s(),;]+)\\s+SET\\b", SQL_FLAGS);

    /** Read-source pattern: every FROM/JOIN/USING <table>. Subqueries (FROM '(') don't match. */
    private static final Pattern P_SOURCE =
            Pattern.compile("\\b(?:FROM|JOIN|USING)\\s+([^\\s(),;]+)", SQL_FLAGS);

    /** CTE definition names: {@code WITH x AS (} / {@code , y AS (}. These are aliases, not assets. */
    private static final Pattern P_CTE_NAME =
            Pattern.compile("(?:\\bWITH\\b|,)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+AS\\s*\\(", SQL_FLAGS);

    /** SQL line comments ({@code -- ...}) and block comments ({@code /* ... *\/}). */
    private static final Pattern P_LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern P_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** Single-quoted string literals (with '' escapes) - replaced so their text can't be mistaken for tables. */
    private static final Pattern P_STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");

    /** A normalized (unquoted) table token must look like a (qualified) identifier. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.$]*");

    /**
     * Table-valued / pseudo tokens that follow FROM/JOIN but are not real sources.
     * Lower-cased for comparison.
     */
    private static final Set<String> SOURCE_STOPWORDS =
            Set.of("dual", "unnest", "lateral", "values", "table", "select",
                    "generate_series", "json_table", "xmltable");

    /**
     * Tokens that can follow a write keyword but are not tables - e.g. Hive's
     * {@code INSERT OVERWRITE [LOCAL] DIRECTORY '/path'} writes to a path, which
     * DLAP does not model as a catalog asset.
     */
    private static final Set<String> TARGET_STOPWORDS = Set.of("directory", "local");

    @Override
    public List<LineageEdge> extract(Map<String, Object> normalizedEvent) {
        if (normalizedEvent == null || normalizedEvent.isEmpty()) {
            return List.of();
        }

        // (a) Canonical sourceResources[] -> targetResources[] cross-product.
        List<LineageEdge> canonical = fromResourceLists(normalizedEvent);
        if (!canonical.isEmpty()) {
            return canonical;
        }

        // (b) BigQuery-structured referencedTables -> destinationTable.
        List<LineageEdge> bigQuery = fromBigQuery(normalizedEvent);
        if (!bigQuery.isEmpty()) {
            return bigQuery;
        }

        // (c) Best-effort parse of a raw SQL statement.
        List<LineageEdge> sql = fromSqlText(normalizedEvent);
        if (!sql.isEmpty()) {
            return sql;
        }

        // (d) No lineage present.
        return List.of();
    }

    /**
     * (a) Every URI in {@code sourceResources} paired with every URI in
     * {@code targetResources}. Relationship comes from the event
     * {@code operation} (default {@code QUERY}).
     */
    private List<LineageEdge> fromResourceLists(Map<String, Object> event) {
        List<String> sources = uris(event.get("sourceResources"));
        List<String> targets = uris(event.get("targetResources"));
        if (sources.isEmpty() || targets.isEmpty()) {
            return List.of();
        }

        String relationship = relationshipFrom(event, DEFAULT_RELATIONSHIP);
        Map<String, Object> details = baseDetails(event);

        List<LineageEdge> edges = new ArrayList<>();
        for (String upstream : sources) {
            for (String downstream : targets) {
                if (isDistinctPair(upstream, downstream)) {
                    edges.add(new LineageEdge(upstream, downstream, relationship, copy(details)));
                }
            }
        }
        return edges;
    }

    /**
     * (b) BigQuery job/query lineage: each referenced table -> the destination
     * table. Searches the common audit-log locations for both fields under
     * {@code rawEvent}.
     */
    private List<LineageEdge> fromBigQuery(Map<String, Object> event) {
        Map<String, Object> raw = asMap(event.get("rawEvent"));
        if (raw == null) {
            return List.of();
        }

        // referencedTables / destinationTable can sit directly on rawEvent or
        // one level down under jobStatistics (classic BigQueryAuditMetadata).
        Map<String, Object> stats = asMap(raw.get("jobStatistics"));

        List<String> referenced = firstNonEmpty(
                tableUris(raw.get("referencedTables")),
                stats == null ? List.of() : tableUris(stats.get("referencedTables")));

        String destination = firstNonBlank(
                tableUri(raw.get("destinationTable")),
                stats == null ? null : tableUri(stats.get("destinationTable")));

        if (referenced.isEmpty() || destination == null) {
            return List.of();
        }

        String relationship = relationshipFrom(event, DEFAULT_RELATIONSHIP);
        Map<String, Object> details = baseDetails(event);
        String jobId = firstNonBlank(str(raw.get("jobId")), stats == null ? null : str(stats.get("jobId")));
        if (jobId != null) {
            details.put("jobId", jobId);
        }

        // C7: when the event names a first-class job asset, route lineage THROUGH it:
        // every referenced table -> job, and job -> destination - so the job is a real
        // endpoint (a BIGQUERY_JOB asset), not an invisible transform. The job URI is
        // taken from an explicit carrier (jobUri / targetResource.id that names a job);
        // absent that, fall back to the classic direct source -> destination edges so
        // existing behavior is unchanged.
        String jobUri = jobUri(event, raw, jobId);
        List<LineageEdge> edges = new ArrayList<>();
        if (jobUri != null) {
            for (String upstream : referenced) {
                if (isDistinctPair(upstream, jobUri)) {
                    edges.add(new LineageEdge(upstream, jobUri, relationship, copy(details)));
                }
            }
            if (isDistinctPair(jobUri, destination)) {
                edges.add(new LineageEdge(jobUri, destination, relationship, copy(details)));
            }
            if (!edges.isEmpty()) {
                return edges;
            }
        }

        for (String upstream : referenced) {
            if (isDistinctPair(upstream, destination)) {
                edges.add(new LineageEdge(upstream, destination, relationship, copy(details)));
            }
        }
        return edges;
    }

    /**
     * Resolve the URI of the job asset this event ran, if the event names one.
     * A job endpoint is only synthesized when the event carries an explicit job
     * identity - a top-level {@code jobUri}, or a {@code targetResource} whose
     * {@code kind} marks it a job (so a table {@code targetResource} is never
     * mistaken for one). Returns {@code null} when no job asset is named.
     */
    private static String jobUri(Map<String, Object> event, Map<String, Object> raw, String jobId) {
        String explicit = firstNonBlank(str(event.get("jobUri")),
                raw == null ? null : str(raw.get("jobUri")));
        if (explicit != null) {
            return explicit;
        }
        Map<String, Object> target = asMap(event.get("targetResource"));
        if (target != null) {
            String kind = str(target.get("kind"));
            String id = str(target.get("id"));
            if (id != null && kind != null && kind.toLowerCase(Locale.ROOT).contains("job")) {
                return id;
            }
        }
        return null;
    }

    /**
     * (c) Best-effort lineage from a raw SQL statement. Finds the single write
     * target and pairs every read source with it. Returns empty unless BOTH a
     * target and at least one distinct source are present - so pure {@code SELECT}s
     * and source-less DDL/DML produce nothing (no false lineage).
     */
    private List<LineageEdge> fromSqlText(Map<String, Object> event) {
        String rawSql = sqlText(event);
        if (rawSql == null) {
            return List.of();
        }
        // Strip comments and string literals first so their text (which may itself
        // contain the word FROM, or a table-looking token) can't be misread as a
        // real table reference.
        String sql = stripNoise(rawSql);

        Target target = writeTarget(sql);
        if (target == null) {
            return List.of();
        }

        // Names defined by WITH ... AS (...) are query-local aliases, not catalog
        // assets; excluding them stops phantom "cte -> target" edges.
        Set<String> cteNames = cteNames(sql);

        // Distinct, order-preserving read sources, minus the target, CTE aliases, stopwords.
        Set<String> sources = new LinkedHashSet<>();
        Matcher m = P_SOURCE.matcher(sql);
        while (m.find()) {
            String src = normalizeTable(m.group(1));
            if (src != null
                    && !SOURCE_STOPWORDS.contains(src.toLowerCase(Locale.ROOT))
                    && !cteNames.contains(src.toLowerCase(Locale.ROOT))
                    && !src.equalsIgnoreCase(target.table)) {
                sources.add(src);
            }
        }
        if (sources.isEmpty()) {
            return List.of();
        }

        // Uppercase the relationship consistently with the (a)/(b) paths so the same
        // logical operation de-dupes to one relationshipType downstream.
        String relationship = relationshipFrom(event, target.relationship);
        Map<String, Object> details = baseDetails(event);
        details.put("lineageSource", "sql-parse");

        List<LineageEdge> edges = new ArrayList<>();
        for (String upstream : sources) {
            if (isDistinctPair(upstream, target.table)) {
                edges.add(new LineageEdge(upstream, target.table, relationship, copy(details)));
            }
        }
        if (!edges.isEmpty()) {
            log.debug("Derived {} lineage edge(s) from SQL text ({} -> {})",
                    edges.size(), sources, target.table);
        }
        return edges;
    }

    /** The raw statement from any of the known carriers (top-level or under rawEvent). */
    private static String sqlText(Map<String, Object> event) {
        String direct = firstNonBlank(
                str(event.get("query")),
                str(event.get("sql")),
                str(event.get("queryText")),
                str(event.get("statement")));
        if (direct != null) {
            return direct;
        }
        Map<String, Object> raw = asMap(event.get("rawEvent"));
        if (raw == null) {
            return null;
        }
        return firstNonBlank(
                str(raw.get("query")),
                str(raw.get("sql")),
                str(raw.get("queryText")),
                str(raw.get("statement")));
    }

    /** Immutable (table, relationship) pair for a resolved write target. */
    private record Target(String table, String relationship) { }

    /** First matching write target in precedence order, or {@code null}. */
    private static Target writeTarget(String sql) {
        Target t;
        if ((t = match(P_INSERT_OVERWRITE, sql, "INSERT")) != null) return t;
        if ((t = match(P_INSERT_INTO, sql, "INSERT")) != null) return t;
        if ((t = match(P_CREATE_AS, sql, "CREATE_AS")) != null) return t;
        if ((t = match(P_MERGE, sql, "MERGE")) != null) return t;
        if ((t = match(P_UPDATE, sql, "UPDATE")) != null) return t;
        return null;
    }

    private static Target match(Pattern pattern, String sql, String relationship) {
        Matcher m = pattern.matcher(sql);
        if (m.find()) {
            String table = normalizeTable(m.group(1));
            if (table != null && !TARGET_STOPWORDS.contains(table.toLowerCase(Locale.ROOT))) {
                return new Target(table, relationship);
            }
        }
        return null;
    }

    /** Remove block/line comments and single-quoted string literals (heuristic lexing). */
    private static String stripNoise(String sql) {
        String s = P_BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        s = P_LINE_COMMENT.matcher(s).replaceAll(" ");
        s = P_STRING_LITERAL.matcher(s).replaceAll(" ");
        return s;
    }

    /** Lower-cased set of CTE names defined by {@code WITH x AS (} / {@code , y AS (}. */
    private static Set<String> cteNames(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = P_CTE_NAME.matcher(sql);
        while (m.find()) {
            names.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Strip surrounding quoting (backtick, double-quote, single-quote, brackets)
     * from each dotted segment and require the result to be a plain identifier;
     * returns {@code null} for anything that doesn't look like a table name.
     */
    private static String normalizeTable(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        // Drop a trailing statement terminator that slipped into the token.
        while (s.endsWith(";") || s.endsWith(",")) {
            s = s.substring(0, s.length() - 1);
        }
        // Remove quoting characters wherever they appear in the (possibly qualified) name.
        s = s.replace("`", "").replace("\"", "").replace("'", "")
                .replace("[", "").replace("]", "");
        if (s.isEmpty() || !IDENTIFIER.matcher(s).matches()) {
            return null;
        }
        return s;
    }

    /** Base provenance carried on every edge: the event operation. */
    private static Map<String, Object> baseDetails(Map<String, Object> event) {
        Map<String, Object> details = new LinkedHashMap<>();
        String operation = str(event.get("operation"));
        if (operation != null) {
            details.put("operation", operation);
        }
        return details;
    }

    /**
     * Relationship type from the event's {@code operation} or BigQuery
     * {@code jobType}, upper-cased; {@code fallback} when neither is present.
     */
    private static String relationshipFrom(Map<String, Object> event, String fallback) {
        String op = firstNonBlank(str(event.get("operation")), str(event.get("jobType")));
        if (op == null) {
            Map<String, Object> raw = asMap(event.get("rawEvent"));
            if (raw != null) {
                op = firstNonBlank(str(raw.get("jobType")), str(raw.get("operation")));
            }
        }
        return op == null ? fallback : op.toUpperCase(java.util.Locale.ROOT);
    }

    /** Extract {@code id}/{@code uri} strings from a list of resource maps (or plain strings). */
    private static List<String> uris(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object element : list) {
            String uri = null;
            if (element instanceof Map<?, ?> m) {
                uri = firstNonBlank(str(m.get("id")), str(m.get("uri")));
            } else {
                uri = str(element);
            }
            if (uri != null) {
                out.add(uri);
            }
        }
        return out;
    }

    /** BigQuery table references -> {@code project.dataset.table} URIs. */
    private static List<String> tableUris(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object element : list) {
            String uri = tableUri(element);
            if (uri != null) {
                out.add(uri);
            }
        }
        return out;
    }

    /**
     * Normalize a single BigQuery table reference to a stable URI. Accepts a
     * plain string, or a map with either a pre-built {@code id}/{@code uri} or
     * the structured {@code projectId}/{@code datasetId}/{@code tableId} triple.
     */
    private static String tableUri(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> m) {
            String direct = firstNonBlank(str(m.get("id")), str(m.get("uri")));
            if (direct != null) {
                return direct;
            }
            String project = str(m.get("projectId"));
            String dataset = str(m.get("datasetId"));
            String table = str(m.get("tableId"));
            if (project != null && dataset != null && table != null) {
                return "projects/" + project + "/datasets/" + dataset + "/tables/" + table;
            }
            return null;
        }
        return str(value);
    }

    private static boolean isDistinctPair(String upstream, String downstream) {
        if (upstream == null || downstream == null) {
            return false;
        }
        if (upstream.equals(downstream)) {
            // A resource reading and writing itself is not lineage; drop it
            // to avoid self-loops in the graph.
            log.debug("Skipping self-referential lineage edge for {}", upstream);
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private static List<String> firstNonEmpty(List<String> a, List<String> b) {
        return a.isEmpty() ? b : a;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
