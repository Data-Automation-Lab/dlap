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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.fixtures.gen.BqJobsRowGenerator;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.providers.gcp.bigquery.BigQueryEmulator;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The parser's reads equal the zero-access scan's reads (GCP-02 acceptance, "results equal SCAN-01 on
 * the same fixtures").
 *
 * <p>SCAN-01's real SQL, {@code scripts/scan/bigquery/last_read.sql}, runs in a BigQuery emulator
 * (goccy/bigquery-emulator, pinned by digest) over the same rows the parser reads. The emulator has no
 * {@code INFORMATION_SCHEMA.JOBS}, so the test makes four mechanical text substitutions, each asserted
 * to have happened, that point the view references at ordinary tables of the same shape, and fixes
 * "now" so the 180-day window is stable. Nothing else in the SQL changes.
 *
 * <p>Per table the scan reports the read count, the last read and the readers by type, with Google
 * service agents counted apart. The test derives the same numbers from the DATA_READ events the
 * parser emits for successful jobs and requires them to be equal, for the hand-written fixture rows
 * and for a seeded stream of 300 generated jobs. Needs Docker; without it the container fails to
 * start and the test errors, which counts as unverified (plan rule R11).
 *
 * <p>What this does not prove: that the emulator's SQL engine behaves like BigQuery's, and that a real
 * JOBS view fills {@code destination_table} and {@code referenced_tables} the way the rows here do.
 * That is the live check on a real project, which no emulator replaces (plan critique item 18).
 */
class BqJobsScanOracleTest {

    private static final String PROJECT = BigQueryEmulator.PROJECT;
    private static final String NOW = "2026-09-14 00:00:00+00";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final MappingSpec MAPPING = AbstractParserContractTest.classpathMapping(BqJobsFormat.FAMILY);
    private static final DefaultParserRegistry REGISTRY =
            AbstractParserContractTest.registryOf(MAPPING, List.of(new BqJobsParser(MAPPING)));
    private static final BindingConfig BINDING = MAPPING.defaultBinding("scan-oracle");
    private static final CanonicalEventMapper MAPPER = new CanonicalEventMapper(
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));

    @BeforeAll
    static void startEmulator() throws Exception {
        BigQueryEmulator.start();
        createInventoryTables();
    }

    // ------------------------------------------------------------------
    // The two comparisons
    // ------------------------------------------------------------------

    @Test
    void theScanAndTheParserCountTheSameReadsOnTheHandWrittenFixtureRows() throws Exception {
        List<ObjectNode> rows = new ArrayList<>();
        for (FixtureCase c : FixtureCatalog.fromClasspath().cases("bqjobs")) {
            if (!c.negative()) {
                rows.add((ObjectNode) JSON.readTree(c.inputText()));
            }
        }
        assertThat(rows).as("positive bqjobs fixture rows").hasSizeGreaterThan(25);

        Map<String, Stats> scan = runScan("jobs_fixtures", rows);
        Map<String, Stats> parser = parserStats(rows);

        assertThat(parser).as("tables the parser read").isNotEmpty();
        assertThat(scan).isEqualTo(parser);
    }

    @Test
    void theScanAndTheParserCountTheSameReadsOnAGeneratedStream() throws Exception {
        List<ObjectNode> rows = BqJobsRowGenerator.generate(20_261_020L, 300);
        // The stream exercises every rule the comparison is about; if the generator changes, this fails first.
        assertThat(count(rows, r -> r.path("cache_hit").asBoolean(false))).as("cache hits").isGreaterThan(5);
        assertThat(count(rows, r -> "SCRIPT".equals(r.path("statement_type").asText()))).as("script parents").isGreaterThan(5);
        assertThat(count(rows, r -> r.hasNonNull("parent_job_id"))).as("script children").isGreaterThan(5);
        assertThat(count(rows, r -> r.hasNonNull("error_result"))).as("failed jobs").isGreaterThan(5);
        assertThat(count(rows, r -> !"DONE".equals(r.path("state").asText()))).as("running jobs").isGreaterThan(3);
        assertThat(count(rows, r -> r.path("referenced_logical_views").size() > 0)).as("jobs with views").isGreaterThan(5);
        assertThat(count(rows, r -> "MERGE".equals(r.path("statement_type").asText())
                || "UPDATE".equals(r.path("statement_type").asText()))).as("DML that references its own target").isGreaterThan(3);

        Map<String, Stats> scan = runScan("jobs_generated", rows);
        Map<String, Stats> parser = parserStats(rows);

        assertThat(parser.size()).as("tables with reads").isGreaterThan(8);
        assertThat(parser.values().stream().mapToLong(Stats::readCount).sum()).isGreaterThan(100);
        assertThat(parser.values().stream().mapToLong(Stats::systemReadCount).sum()).as("system reads").isGreaterThan(0);
        assertThat(parser.values().stream().mapToLong(Stats::readersUnknown).sum()).as("unknown callers").isGreaterThan(0);
        assertThat(scan).isEqualTo(parser);
    }

    @Test
    void theNoReadListOfTheParserIsTheNoReadListOfTheScan() throws IOException {
        String sql = scanSql();
        for (String statement : BqJobsParser.NO_READ_STATEMENTS) {
            assertThat(sql).as("SCAN-01 excludes %s", statement).contains("'" + statement + "'");
        }
        // The other direction: every statement type the SQL lists is on the parser's list.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "statement_type, ''\\) NOT IN \\((.*?)\\n\\s*\\)", java.util.regex.Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).as("the NOT IN list of read_jobs").isTrue();
        Set<String> inSql = new HashSet<>();
        java.util.regex.Matcher item = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(m.group(1));
        while (item.find()) {
            inSql.add(item.group(1));
        }
        assertThat(BqJobsParser.NO_READ_STATEMENTS).containsExactlyInAnyOrderElementsOf(inSql);
    }

    private static long count(List<ObjectNode> rows, java.util.function.Predicate<ObjectNode> p) {
        return rows.stream().filter(p).count();
    }

    // ------------------------------------------------------------------
    // What the parser says
    // ------------------------------------------------------------------

    /** The numbers the scan reports per table, in the scan's words. */
    record Stats(long readCount, long systemReadCount, String lastReadAt, String lastSystemReadAt, long readersHuman,
                 long readersService, long readersSystem, long readersUnknown) {
    }

    private static Map<String, Stats> parserStats(List<ObjectNode> rows) {
        Map<String, Set<String>> jobs = new TreeMap<>();
        Map<String, Set<String>> systemJobs = new TreeMap<>();
        Map<String, Instant> last = new TreeMap<>();
        Map<String, Instant> lastSystem = new TreeMap<>();
        Map<String, Map<String, Set<String>>> readers = new TreeMap<>();
        for (ObjectNode row : rows) {
            RawRecord record = RawRecord.builder().bindingId(BINDING.bindingId()).sourceKind(BqJobsFormat.SOURCE_KIND)
                    .envelope(BqJobsFormat.ENVELOPE).ref(RawRef.of("ORACLE", "row:" + row.path("job_id").asText(), -1))
                    .mode(IngestMode.REPLAY).fetchedAt(Instant.parse("2030-01-01T00:00:00Z")).json(row).build();
            for (DlapLogEvent e : MAPPER.events(REGISTRY.parse(record, BINDING))) {
                if (e.getResultStatus() != ResultStatus.SUCCESS || e.getEventCategory() != EventCategory.EVENT_CATEGORY_DATA_READ) {
                    continue;
                }
                String table = e.getTargetResource().getProject() + "." + datasetOf(e) + "." + e.getTargetResource().getName();
                String job = e.getRequestMetadata().getRequestId();
                String principal = e.getPrincipal().getId().isEmpty() ? "(none)" : e.getPrincipal().getId();
                Instant time = Instant.ofEpochSecond(e.getEventTime().getSeconds());
                String type = switch (e.getPrincipal().getType()) {
                    case SYSTEM -> "system";
                    case SERVICE_ACCOUNT -> "service";
                    case USER -> "human";
                    default -> "unknown";
                };
                if (e.getPrincipal().getType() == PrincipalType.SYSTEM) {
                    systemJobs.computeIfAbsent(table, k -> new HashSet<>()).add(job);
                    lastSystem.merge(table, time, (a, b) -> a.isAfter(b) ? a : b);
                } else {
                    jobs.computeIfAbsent(table, k -> new HashSet<>()).add(job);
                    last.merge(table, time, (a, b) -> a.isAfter(b) ? a : b);
                }
                readers.computeIfAbsent(table, k -> new TreeMap<>()).computeIfAbsent(type, k -> new HashSet<>()).add(principal);
            }
        }
        Set<String> tables = new java.util.TreeSet<>(jobs.keySet());
        tables.addAll(systemJobs.keySet());
        Map<String, Stats> out = new TreeMap<>();
        for (String t : tables) {
            Map<String, Set<String>> r = readers.getOrDefault(t, Map.of());
            out.put(t, new Stats(jobs.getOrDefault(t, Set.of()).size(), systemJobs.getOrDefault(t, Set.of()).size(),
                    format(last.get(t)), format(lastSystem.get(t)), r.getOrDefault("human", Set.of()).size(),
                    r.getOrDefault("service", Set.of()).size(), r.getOrDefault("system", Set.of()).size(),
                    r.getOrDefault("unknown", Set.of()).size()));
        }
        return out;
    }

    private static String datasetOf(DlapLogEvent e) {
        // resource_path is projects/{p}/datasets/{d}/tables/{t}
        String[] parts = e.getTargetResource().getResourcePath().split("/");
        return parts[3];
    }

    private static String format(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // ------------------------------------------------------------------
    // What the scan says
    // ------------------------------------------------------------------

    private static Map<String, Stats> runScan(String jobsTable, List<ObjectNode> rows) throws Exception {
        createJobsTable(jobsTable);
        insert(jobsTable, rows);
        String sql = scanSql().replace("`proj-fx-demo.scan.jobs`", "`proj-fx-demo.scan." + jobsTable + "`");
        JsonNode result = BigQueryEmulator.post("/queries", JSON.readTree(JSON.writeValueAsString(Map.of(
                "query", sql, "useLegacySql", false, "maxResults", 100000))));
        assertThat(result.path("jobComplete").asBoolean()).as("the scan SQL ran: %s", result).isTrue();
        List<String> names = new ArrayList<>();
        result.path("schema").path("fields").forEach(f -> names.add(f.path("name").asText()));
        Map<String, Stats> out = new TreeMap<>();
        for (JsonNode row : result.path("rows")) {
            Map<String, String> v = new TreeMap<>();
            for (int i = 0; i < names.size(); i++) {
                JsonNode cell = row.path("f").get(i).get("v");
                v.put(names.get(i), cell == null || cell.isNull() ? null : cell.asText());
            }
            String key = v.get("table_catalog") + "." + v.get("table_schema") + "." + v.get("table_name");
            out.put(key, new Stats(Long.parseLong(v.get("read_count")), Long.parseLong(v.get("system_read_count")),
                    v.get("last_read_at"), v.get("last_system_read_at"), Long.parseLong(v.get("readers_human")),
                    Long.parseLong(v.get("readers_service")), Long.parseLong(v.get("readers_system")),
                    Long.parseLong(v.get("readers_unknown"))));
        }
        // The scan lists a table that only system principals read with read_count 0; the parser side does too.
        return out;
    }

    /**
     * SCAN-01's SQL with the four substitutions: the view and the three inventory views become
     * ordinary tables of the same columns, and CURRENT_TIMESTAMP() becomes a fixed instant.
     */
    private static String scanSql() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("scripts/scan/bigquery/last_read.sql"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("scripts/scan/bigquery/last_read.sql above the working directory").isNotNull();
        String sql = Files.readString(dir.resolve("scripts/scan/bigquery/last_read.sql")).replace("\r\n", "\n");
        sql = once(sql, "`region-us`.INFORMATION_SCHEMA.JOBS_BY_PROJECT", "`proj-fx-demo.scan.jobs`");
        sql = once(sql, "`region-us`.INFORMATION_SCHEMA.TABLE_STORAGE", "`proj-fx-demo.scan.table_storage`");
        sql = once(sql, "`region-us`.INFORMATION_SCHEMA.TABLE_OPTIONS", "`proj-fx-demo.scan.table_options`");
        sql = once(sql, "`region-us`.INFORMATION_SCHEMA.TABLES", "`proj-fx-demo.scan.tables`");
        int clocks = sql.split("CURRENT_TIMESTAMP\\(\\)", -1).length - 1;
        assertThat(clocks).as("CURRENT_TIMESTAMP() calls in the scan SQL").isEqualTo(2);
        return sql.replace("CURRENT_TIMESTAMP()", "TIMESTAMP '" + NOW + "'");
    }

    private static String once(String sql, String from, String to) {
        int first = sql.indexOf(from);
        assertThat(first).as("%s in the scan SQL", from).isGreaterThanOrEqualTo(0);
        assertThat(sql.indexOf(from, first + 1)).as("%s occurs once", from).isEqualTo(-1);
        return sql.replace(from, to);
    }

    // ------------------------------------------------------------------
    // Emulator plumbing
    // ------------------------------------------------------------------

    private static JsonNode fields(String... nameType) {
        ArrayNode a = JSON.createArrayNode();
        for (int i = 0; i < nameType.length; i += 2) {
            a.addObject().put("name", nameType[i]).put("type", nameType[i + 1]);
        }
        return a;
    }

    private static ObjectNode tableRef() {
        ObjectNode f = JSON.createObjectNode();
        f.set("fields", fields("project_id", "STRING", "dataset_id", "STRING", "table_id", "STRING"));
        return f;
    }

    private static void createInventoryTables() throws Exception {
        createTable("tables", fields("table_catalog", "STRING", "table_schema", "STRING", "table_name", "STRING",
                "table_type", "STRING", "creation_time", "TIMESTAMP"));
        createTable("table_storage", fields("project_id", "STRING", "table_schema", "STRING", "table_name", "STRING",
                "storage_last_modified_time", "TIMESTAMP", "total_logical_bytes", "INTEGER", "total_rows", "INTEGER",
                "deleted", "BOOLEAN"));
        createTable("table_options", fields("table_catalog", "STRING", "table_schema", "STRING", "table_name", "STRING",
                "option_name", "STRING", "option_value", "STRING"));
    }

    private static void createJobsTable(String name) throws Exception {
        ArrayNode schema = (ArrayNode) fields("project_id", "STRING", "job_id", "STRING", "parent_job_id", "STRING",
                "creation_time", "TIMESTAMP", "end_time", "TIMESTAMP", "job_type", "STRING", "state", "STRING",
                "statement_type", "STRING", "cache_hit", "BOOLEAN", "user_email", "STRING", "principal_subject", "STRING");
        schema.addObject().put("name", "error_result").put("type", "RECORD").set("fields", fields("reason", "STRING"));
        for (String column : List.of("destination_table", "referenced_tables", "referenced_logical_views")) {
            ObjectNode f = schema.addObject();
            f.put("name", column).put("type", "RECORD");
            f.set("fields", tableRef().get("fields"));
            if (!"destination_table".equals(column)) {
                f.put("mode", "REPEATED");
            }
        }
        createTable(name, schema);
    }

    private static void createTable(String name, JsonNode schema) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("tableReference").put("projectId", PROJECT).put("datasetId", "scan").put("tableId", name);
        body.putObject("schema").set("fields", schema);
        BigQueryEmulator.post("/datasets/scan/tables", body);
    }

    private static void insert(String table, List<ObjectNode> rows) throws Exception {
        List<String> columns = List.of("project_id", "job_id", "parent_job_id", "creation_time", "end_time", "job_type",
                "state", "statement_type", "cache_hit", "error_result", "destination_table", "referenced_tables",
                "referenced_logical_views", "user_email", "principal_subject");
        for (int from = 0; from < rows.size(); from += 100) {
            ObjectNode body = JSON.createObjectNode();
            ArrayNode batch = body.putArray("rows");
            for (ObjectNode row : rows.subList(from, Math.min(rows.size(), from + 100))) {
                ObjectNode json = JSON.createObjectNode();
                for (String c : columns) {
                    JsonNode v = row.get(c);
                    if (v == null || v.isNull()) {
                        continue;
                    }
                    if ("error_result".equals(c)) {
                        // The emulator table keeps only the reason; whether an error exists is all the scan asks.
                        json.putObject(c).put("reason", v.path("reason").asText("error"));
                    } else {
                        json.set(c, v);
                    }
                }
                batch.addObject().set("json", json);
            }
            JsonNode response = BigQueryEmulator.post("/datasets/scan/tables/" + table + "/insertAll", body);
            assertThat(response.path("insertErrors").isMissingNode()).as("insertAll errors: %s", response).isTrue();
        }
    }
}
