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

package com.dlap.logingest.providers.gcp.bigquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.NoCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The production gateway and row converter against a BigQuery API: the Google client library's own
 * wire handling (timestamps, nested and repeated records, nulls, named timestamp parameters, paging
 * through {@code iterateAll}) and its failures, with the goccy emulator as the server. The emulator
 * has no JOBS view, so the SQL here reads an ordinary table of the same column shapes.
 *
 * <p>Needs Docker (plan rule R11). It proves the client-side half of the transport on a real client;
 * the server-side half, the view itself, is the live check on a real project.
 */
class BigQueryGatewayEmulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "`" + BigQueryEmulator.PROJECT + ".scan.gateway_rows`";
    private static BigQueryJobsViewGateway gateway;

    @BeforeAll
    static void start() throws Exception {
        BigQueryEmulator.start();
        ObjectNode table = JSON.createObjectNode();
        table.putObject("tableReference").put("projectId", BigQueryEmulator.PROJECT).put("datasetId", "scan")
                .put("tableId", "gateway_rows");
        ArrayNode fields = table.putObject("schema").putArray("fields");
        fields.addObject().put("name", "job_id").put("type", "STRING");
        fields.addObject().put("name", "end_time").put("type", "TIMESTAMP");
        fields.addObject().put("name", "cache_hit").put("type", "BOOLEAN");
        fields.addObject().put("name", "total_bytes_billed").put("type", "INTEGER");
        fields.addObject().put("name", "parent_job_id").put("type", "STRING");
        ObjectNode error = fields.addObject();
        error.put("name", "error_result").put("type", "RECORD");
        error.putArray("fields").addObject().put("name", "reason").put("type", "STRING");
        ObjectNode refs = fields.addObject();
        refs.put("name", "referenced_tables").put("type", "RECORD").put("mode", "REPEATED");
        ArrayNode refFields = refs.putArray("fields");
        refFields.addObject().put("name", "project_id").put("type", "STRING");
        refFields.addObject().put("name", "dataset_id").put("type", "STRING");
        refFields.addObject().put("name", "table_id").put("type", "STRING");
        BigQueryEmulator.post("/datasets/scan/tables", table);

        ObjectNode insert = JSON.createObjectNode();
        ArrayNode rows = insert.putArray("rows");
        ObjectNode a = rows.addObject().putObject("json");
        a.put("job_id", "job_a").put("end_time", "2026-10-01T10:00:00.250000Z").put("cache_hit", false)
                .put("total_bytes_billed", 10485760);
        a.putObject("error_result").put("reason", "accessDenied");
        a.putArray("referenced_tables").addObject().put("project_id", "p").put("dataset_id", "d").put("table_id", "t1");
        ObjectNode b = rows.addObject().putObject("json");
        b.put("job_id", "job_b").put("end_time", "2026-10-01T10:05:00Z").put("cache_hit", true);
        b.putArray("referenced_tables");
        ObjectNode c = rows.addObject().putObject("json");
        c.put("job_id", "job_c").put("end_time", "2026-10-02T00:00:00Z");
        BigQueryEmulator.post("/datasets/scan/tables/gateway_rows/insertAll", insert);

        BigQuery client = BigQueryOptions.newBuilder().setProjectId(BigQueryEmulator.PROJECT)
                .setHost(BigQueryEmulator.host()).setCredentials(NoCredentials.getInstance()).build().getService();
        gateway = new BigQueryJobsViewGateway(project -> client);
    }

    private static List<ObjectNode> rows(String sql, Map<String, Instant> parameters) {
        var it = gateway.rows(new JobsViewGateway.Query(BigQueryEmulator.PROJECT, "US", sql, parameters));
        List<ObjectNode> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    @Test
    void rowsComeBackAsJsonWithTimestampsRecordsArraysAndNulls() {
        List<ObjectNode> rows = rows("SELECT job_id, end_time, cache_hit, total_bytes_billed, parent_job_id, error_result, "
                + "referenced_tables FROM " + SOURCE + " WHERE end_time >= @from AND end_time < @to ORDER BY end_time, job_id",
                Map.of("from", Instant.parse("2026-10-01T00:00:00Z"), "to", Instant.parse("2026-10-02T00:00:00Z")));

        assertThat(rows).hasSize(2);
        ObjectNode first = rows.get(0);
        assertThat(first.get("job_id").asText()).isEqualTo("job_a");
        assertThat(first.get("end_time").asText()).isEqualTo("2026-10-01T10:00:00.250Z");
        assertThat(first.get("cache_hit").isBoolean()).isTrue();
        assertThat(first.get("total_bytes_billed").asLong()).isEqualTo(10485760L);
        assertThat(first.get("parent_job_id").isNull()).as("a null column stays a null").isTrue();
        assertThat(first.get("error_result").get("reason").asText()).isEqualTo("accessDenied");
        JsonNode table = first.get("referenced_tables").get(0);
        assertThat(table.get("project_id").asText()).isEqualTo("p");
        assertThat(table.get("dataset_id").asText()).isEqualTo("d");
        assertThat(table.get("table_id").asText()).isEqualTo("t1");
        ObjectNode second = rows.get(1);
        assertThat(second.get("job_id").asText()).isEqualTo("job_b");
        assertThat(second.get("cache_hit").asBoolean()).isTrue();
        assertThat(second.get("referenced_tables").isArray()).isTrue();
        assertThat(second.get("referenced_tables")).isEmpty();
        assertThat(second.get("error_result").isNull()).isTrue();
    }

    @Test
    void theTimestampWindowIsHalfOpen() {
        List<ObjectNode> rows = rows("SELECT job_id FROM " + SOURCE + " WHERE end_time >= @from AND end_time < @to ORDER BY job_id",
                Map.of("from", Instant.parse("2026-10-01T10:05:00Z"), "to", Instant.parse("2026-10-02T00:00:00Z")));

        assertThat(rows).extracting(r -> r.get("job_id").asText()).containsExactly("job_b");
    }

    @Test
    void aDryRunOfSelectStarReturnsTheColumnNamesInOrder() {
        List<String> columns = gateway.columns(new JobsViewGateway.Query(BigQueryEmulator.PROJECT, "US",
                "SELECT * FROM " + SOURCE, Map.of()));

        assertThat(columns).containsExactly("job_id", "end_time", "cache_hit", "total_bytes_billed", "parent_job_id",
                "error_result", "referenced_tables");
    }

    @Test
    void aRefusedQueryBecomesAViewFailureWithoutTheSqlInIt() {
        assertThatThrownBy(() -> rows("SELECT secret_marker FROM `" + BigQueryEmulator.PROJECT + ".scan.no_such_table`", Map.of()))
                .isInstanceOfSatisfying(JobsViewGateway.ViewFailure.class, e -> {
                    assertThat(e.getMessage()).startsWith("BigQuery refused the JOBS query");
                    assertThat(e.getMessage()).doesNotContain("secret_marker");
                });
    }
}
