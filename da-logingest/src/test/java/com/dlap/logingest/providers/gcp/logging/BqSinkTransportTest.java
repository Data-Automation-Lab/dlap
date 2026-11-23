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

package com.dlap.logingest.providers.gcp.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.gcpaudit.GcpAuditFormat;
import com.dlap.logingest.parse.gcpaudit.GcpAuditRegistryForTests;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The BigQuery sink transport against in-memory tables: partitioned and date-sharded tables, the
 * export_errors table, the parameterised window query and identifier checks.
 */
class BqSinkTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATA_ACCESS = "cloudaudit_googleapis_com_data_access";
    private static final FetchWindow WINDOW = new FetchWindow(Instant.parse("2026-09-01T22:00:00Z"),
            Instant.parse("2026-09-02T01:00:00Z"));

    /** In-memory tables: table id to result pages. */
    static final class FakeTables implements BqSinkTransport.SinkTables {
        final Map<String, List<List<ObjectNode>>> tables = new LinkedHashMap<>();
        final List<String> queries = new ArrayList<>();

        FakeTables table(String id, List<List<ObjectNode>> pages) {
            tables.put(id, pages);
            return this;
        }

        @Override
        public List<String> list(StreamContext context, String project, String dataset) {
            return List.copyOf(tables.keySet());
        }

        @Override
        public Iterator<BqSinkTransport.Page> query(StreamContext context, String project, String sql, Instant from,
                                                    Instant to, int pageSize) {
            queries.add(project + "|" + sql + "|" + from + "|" + to + "|" + pageSize);
            String table = sql.substring(sql.lastIndexOf('.') + 1, sql.indexOf('`', sql.lastIndexOf('.')));
            List<BqSinkTransport.Page> pages = new ArrayList<>();
            int i = 0;
            for (List<ObjectNode> rows : tables.getOrDefault(table, List.of())) {
                pages.add(new BqSinkTransport.Page(rows, "page-" + i++));
            }
            return pages.iterator();
        }
    }

    private static ObjectNode row(String insertId, String timestamp) {
        ObjectNode r = JSON.createObjectNode();
        r.put("insertId", insertId);
        r.put("timestamp", timestamp);
        return r;
    }

    private static StreamContext ctx(Map<String, String> location) {
        Map<String, String> loc = new LinkedHashMap<>(location);
        loc.putIfAbsent("dataset", "audit_sink");
        return GcpTransportsForTests.context(BqSinkTransport.ID, GcpAuditFormat.BQ_SINK_ROW, "proj-0d1a0001", loc, Set.of());
    }

    private static List<RawBatch> drain(RawBatchIterator it) {
        List<RawBatch> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    @Test
    void aPartitionedTableIsReadAsItselfAndShardedTablesByTheDaysOfTheWindow() {
        assertThat(BqSinkTransport.select(List.of(DATA_ACCESS, DATA_ACCESS + "_20260901"), DATA_ACCESS, WINDOW))
                .containsExactly(DATA_ACCESS);
        assertThat(BqSinkTransport.select(List.of(DATA_ACCESS + "_20260902", DATA_ACCESS + "_20260831",
                DATA_ACCESS + "_20260901", DATA_ACCESS + "_20260903", DATA_ACCESS + "_backup",
                "cloudaudit_googleapis_com_activity_20260901"), DATA_ACCESS, WINDOW))
                .containsExactly(DATA_ACCESS + "_20260901", DATA_ACCESS + "_20260902");
        assertThat(BqSinkTransport.select(List.of(), DATA_ACCESS, WINDOW)).isEmpty();
    }

    @Test
    void partitionsAreTheAuditTablesPlusExportErrorsUnlessTurnedOff() {
        BqSinkTransport t = new BqSinkTransport(new FakeTables());
        assertThat(t.partitions(ctx(Map.of()))).containsExactly("cloudaudit_googleapis_com_activity", DATA_ACCESS,
                "cloudaudit_googleapis_com_system_event", "cloudaudit_googleapis_com_policy", "export_errors");
        assertThat(t.partitions(ctx(Map.of("tables", DATA_ACCESS + ",export_errors", "include-export-errors", "false"))))
                .containsExactly(DATA_ACCESS);
    }

    @Test
    void eachResultPageIsOneBatchAndTheQueryIsParameterisedAndOrdered() {
        FakeTables tables = new FakeTables()
                .table(DATA_ACCESS + "_20260901", List.of(List.of(row("a", "2026-09-01T22:10:00Z"),
                        row("b", "2026-09-01T23:50:00.123456Z"))))
                .table(DATA_ACCESS + "_20260902", List.of(List.of(row("c", "2026-09-02T00:10:00Z")), List.of()));
        List<RawBatch> batches = drain(new BqSinkTransport(tables).fetch(ctx(Map.of("page-size", "500")), DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), WINDOW));

        assertThat(batches).hasSize(4);
        assertThat(batches.get(0).records()).hasSize(2);
        assertThat(batches.get(0).records()).allSatisfy(r -> {
            assertThat(r.ref().uri()).isEqualTo("bq://proj-0d1a0001/audit_sink/" + DATA_ACCESS + "_20260901");
            assertThat(r.ref().cursor()).isEqualTo("page-0");
            assertThat(r.envelope()).isEqualTo(GcpAuditFormat.BQ_SINK_ROW);
        });
        assertThat(batches.get(0).advanceTo().lowWatermark()).isEqualTo(Instant.parse("2026-09-01T23:50:00.123456Z"));
        assertThat(batches.get(0).advanceTo().state()).containsEntry("table", DATA_ACCESS + "_20260901");
        assertThat(batches.get(1).records()).singleElement()
                .satisfies(r -> assertThat(r.ref().recordIndex()).isEqualTo(2));
        assertThat(batches.get(2).records()).isEmpty();
        assertThat(batches.get(3).records()).isEmpty();
        assertThat(batches.get(3).advanceTo().lowWatermark()).isEqualTo(WINDOW.to());

        assertThat(tables.queries).containsExactly(
                "proj-0d1a0001|SELECT * FROM `proj-0d1a0001.audit_sink." + DATA_ACCESS + "_20260901` WHERE timestamp >= @from"
                        + " AND timestamp < @to ORDER BY timestamp, insertId|" + WINDOW.from() + "|" + WINDOW.to() + "|500",
                "proj-0d1a0001|SELECT * FROM `proj-0d1a0001.audit_sink." + DATA_ACCESS + "_20260902` WHERE timestamp >= @from"
                        + " AND timestamp < @to ORDER BY timestamp, insertId|" + WINDOW.from() + "|" + WINDOW.to() + "|500");
    }

    @Test
    void exportErrorRowsAreReadAndQuarantinedAsAFormatBrokeSignal() {
        ObjectNode error = JSON.createObjectNode();
        error.put("timestamp", "2026-09-01T22:30:00Z");
        error.put("errorMessage", "Schema mismatch: field protopayload_auditlog.metadataJson");
        FakeTables tables = new FakeTables().table("export_errors", List.of(List.of(error)));
        List<RawBatch> batches = drain(new BqSinkTransport(tables).fetch(ctx(Map.of()), "export_errors",
                new CursorPosition(null, null, Map.of()), WINDOW));
        assertThat(tables.queries).singleElement().satisfies(q -> assertThat(q).contains("ORDER BY timestamp|"));
        RawRecord r = batches.get(0).records().get(0);
        String outcome = GcpAuditRegistryForTests.registry()
                .parse(r, BindingConfig.auto("gcp-test", GcpAuditFormat.SOURCE_KIND)).outcomeCode();
        assertThat(outcome).isEqualTo("QUARANTINED:UNKNOWN_ENVELOPE");
    }

    @Test
    void identifiersThatAreNotBigQueryNamesNeverReachAQuery() {
        FakeTables tables = new FakeTables();
        BqSinkTransport t = new BqSinkTransport(tables);
        assertThatThrownBy(() -> t.fetch(ctx(Map.of("dataset", "audit`; DROP TABLE x; --")), DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), WINDOW)).hasMessageContaining("dataset");
        assertThatThrownBy(() -> t.fetch(ctx(Map.of("project", "proj-0d1a0001`")), DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), WINDOW)).hasMessageContaining("project");
        assertThatThrownBy(() -> t.fetch(ctx(Map.of()), "cloudaudit.googleapis.com/activity",
                new CursorPosition(null, null, Map.of()), WINDOW)).hasMessageContaining("table");
        assertThat(tables.queries).isEmpty();
    }

    /** Every row of every positive bq-sink-row fixture case, read as a result row, parses like the case itself. */
    @Test
    void everyBqSinkRowFixtureCaseReadAsAResultPageParsesLikeTheCaseItself() throws IOException {
        int records = 0;
        BindingConfig binding = BindingConfig.auto("gcp-test", GcpAuditFormat.SOURCE_KIND);
        for (FixtureCase c : FixtureCatalog.fromClasspath().cases(GcpAuditFormat.FAMILY)) {
            if (c.negative() || !c.envelope().equals(GcpAuditFormat.BQ_SINK_ROW)) {
                continue;
            }
            List<ObjectNode> rows = new ArrayList<>();
            for (String line : GcpTransportsForTests.records(c)) {
                rows.add((ObjectNode) JSON.readTree(line));
            }
            FakeTables tables = new FakeTables().table(DATA_ACCESS, List.of(rows));
            List<RawBatch> batches = drain(new BqSinkTransport(tables).fetch(ctx(Map.of()), DATA_ACCESS,
                    new CursorPosition(null, null, Map.of()), WINDOW));
            List<RawRecord> read = batches.get(0).records();
            assertThat(read).as(c.id()).hasSize(rows.size());
            for (int i = 0; i < read.size(); i++) {
                RawRecord direct = RawRecord.builder().bindingId("gcp-test").sourceKind(GcpAuditFormat.SOURCE_KIND)
                        .envelope(GcpAuditFormat.BQ_SINK_ROW).ref(read.get(i).ref()).fetchedAt(GcpTransportsForTests.NOW)
                        .json(rows.get(i)).build();
                assertThat(GcpAuditRegistryForTests.registry().parse(read.get(i), binding).outcomeCode()).as(c.id())
                        .isEqualTo(GcpAuditRegistryForTests.registry().parse(direct, binding).outcomeCode());
                records++;
            }
        }
        assertThat(records).as("bq-sink-row records read as result rows").isGreaterThan(50);
    }
}
