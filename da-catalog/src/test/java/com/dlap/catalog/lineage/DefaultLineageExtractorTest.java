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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlap.catalog.lineage.LineageExtractor.LineageEdge;

/**
 * Unit tests for {@link DefaultLineageExtractor}. Plain JUnit with hand-built
 * maps - no Mockito (the ByteBuddy agent is unstable on this box).
 */
class DefaultLineageExtractorTest {

    private final DefaultLineageExtractor extractor = new DefaultLineageExtractor();

    @Test
    void bigQueryStructuredEvent_yieldsReferencedToDestinationEdges() {
        // rawEvent.jobStatistics with two referenced tables and one destination.
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("referencedTables", List.of(
                "projects/p/datasets/raw/tables/orders",
                "projects/p/datasets/raw/tables/customers"));
        stats.put("destinationTable", "projects/p/datasets/mart/tables/orders_enriched");
        stats.put("jobId", "job-123");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("jobStatistics", stats);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("operation", "query");
        event.put("rawEvent", raw);

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(2);
        assertThat(edges).allSatisfy(e -> {
            assertThat(e.downstreamUri()).isEqualTo("projects/p/datasets/mart/tables/orders_enriched");
            assertThat(e.relationshipType()).isEqualTo("QUERY");
            assertThat(e.details()).containsEntry("operation", "query");
            assertThat(e.details()).containsEntry("jobId", "job-123");
        });
        assertThat(edges).extracting(LineageEdge::upstreamUri)
                .containsExactlyInAnyOrder(
                        "projects/p/datasets/raw/tables/orders",
                        "projects/p/datasets/raw/tables/customers");
    }

    @Test
    void bigQueryJobEvent_routesLineageThroughTheJobEndpoint() {
        // C7: when the event names a job asset (jobUri), lineage flows
        // source -> job -> destination so the job is a real endpoint.
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("referencedTables", List.of(
                "projects/p/datasets/raw/tables/orders",
                "projects/p/datasets/raw/tables/customers"));
        stats.put("destinationTable", "projects/p/datasets/mart/tables/orders_enriched");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("jobStatistics", stats);

        String jobUri = "bigquery://projects/p/jobs/job_abc123";
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("operation", "query");
        event.put("jobUri", jobUri);
        event.put("rawEvent", raw);

        List<LineageEdge> edges = extractor.extract(event);

        // Two source -> job edges plus one job -> destination edge.
        assertThat(edges).hasSize(3);
        // The job participates as both a downstream (of sources) and upstream (of dest).
        assertThat(edges).filteredOn(e -> e.downstreamUri().equals(jobUri))
                .extracting(LineageEdge::upstreamUri)
                .containsExactlyInAnyOrder(
                        "projects/p/datasets/raw/tables/orders",
                        "projects/p/datasets/raw/tables/customers");
        assertThat(edges).filteredOn(e -> e.upstreamUri().equals(jobUri))
                .extracting(LineageEdge::downstreamUri)
                .containsExactly("projects/p/datasets/mart/tables/orders_enriched");
        assertThat(edges).allSatisfy(e -> assertThat(e.relationshipType()).isEqualTo("QUERY"));
    }

    @Test
    void bigQueryJobEndpoint_fromTargetResourceKindJob() {
        // A targetResource whose kind marks it a job also yields a job endpoint.
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("referencedTables", List.of("projects/p/datasets/raw/tables/orders"));
        stats.put("destinationTable", "projects/p/datasets/mart/tables/orders_enriched");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("jobStatistics", stats);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("kind", "gcp_bigquery_job");
        target.put("id", "bigquery://projects/p/jobs/job_xyz");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("rawEvent", raw);
        event.put("targetResource", target);

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(2);
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.upstreamUri()).isEqualTo("projects/p/datasets/raw/tables/orders");
            assertThat(e.downstreamUri()).isEqualTo("bigquery://projects/p/jobs/job_xyz");
        });
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.upstreamUri()).isEqualTo("bigquery://projects/p/jobs/job_xyz");
            assertThat(e.downstreamUri()).isEqualTo("projects/p/datasets/mart/tables/orders_enriched");
        });
    }

    @Test
    void bigQueryEventWithoutJobIdentity_keepsDirectTableToTableEdges() {
        // No jobUri / job-kind target -> unchanged classic behavior (source -> dest).
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("referencedTables", List.of("projects/p/datasets/raw/tables/orders"));
        stats.put("destinationTable", "projects/p/datasets/mart/tables/orders_enriched");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("jobStatistics", stats);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("rawEvent", raw);

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("projects/p/datasets/raw/tables/orders");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("projects/p/datasets/mart/tables/orders_enriched");
    }

    @Test
    void bigQueryStructuredEvent_toleratesFieldsDirectlyOnRawEvent() {
        // referencedTables + destinationTable directly on rawEvent (no jobStatistics),
        // expressed as the structured projectId/datasetId/tableId triple.
        Map<String, Object> referenced = new LinkedHashMap<>();
        referenced.put("projectId", "p");
        referenced.put("datasetId", "raw");
        referenced.put("tableId", "events");

        Map<String, Object> destination = new LinkedHashMap<>();
        destination.put("projectId", "p");
        destination.put("datasetId", "mart");
        destination.put("tableId", "events_daily");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("referencedTables", List.of(referenced));
        raw.put("destinationTable", destination);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("rawEvent", raw);

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("projects/p/datasets/raw/tables/events");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("projects/p/datasets/mart/tables/events_daily");
        // No operation on the event -> default relationship.
        assertThat(edges.get(0).relationshipType()).isEqualTo("QUERY");
    }

    @Test
    void canonicalSourceTargetResources_takePrecedence() {
        // Canonical fields present -> BigQuery branch is not even consulted.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("operation", "COPY");
        event.put("sourceResources", List.of(
                Map.of("id", "gs://bucket/in/a.csv"),
                Map.of("uri", "gs://bucket/in/b.csv")));
        event.put("targetResources", List.of(Map.of("id", "bq://p/d/t")));

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(LineageEdge::downstreamUri).containsOnly("bq://p/d/t");
        assertThat(edges).extracting(LineageEdge::upstreamUri)
                .containsExactlyInAnyOrder("gs://bucket/in/a.csv", "gs://bucket/in/b.csv");
        assertThat(edges).allSatisfy(e -> assertThat(e.relationshipType()).isEqualTo("COPY"));
    }

    @Test
    void selfReferentialResource_isDropped() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sourceResources", List.of(Map.of("id", "gs://bucket/x")));
        event.put("targetResources", List.of(Map.of("id", "gs://bucket/x")));

        assertThat(extractor.extract(event)).isEmpty();
    }

    @Test
    void eventWithNoStructuredLineage_yieldsEmpty() {
        // A plain ACCESS event: principal + target, but no source/target resource
        // lists and no BigQuery job structure.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("changeType", "ACCESS");
        event.put("operation", "storage.objects.get");
        event.put("targetResource", Map.of("id", "gs://bucket/report.csv"));
        event.put("rawEvent", Map.of("some", "payload"));

        assertThat(extractor.extract(event)).isEmpty();
    }

    @Test
    void nullOrEmptyEvent_yieldsEmpty() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract(Map.of())).isEmpty();
    }

    // ---- (c) SQL-text parsing ---------------------------------------------

    @Test
    void insertIntoSelect_yieldsSourceToTargetEdge() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query", "INSERT INTO mart.orders_daily SELECT * FROM raw.orders WHERE ts > 0");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("raw.orders");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("mart.orders_daily");
        assertThat(edges.get(0).relationshipType()).isEqualTo("INSERT");
        assertThat(edges.get(0).details()).containsEntry("lineageSource", "sql-parse");
    }

    @Test
    void createTableAsSelect_withMultipleJoins_yieldsEdgePerSource() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sql",
                "CREATE OR REPLACE TABLE analytics.enriched AS\n"
                        + "SELECT o.id, c.name, p.sku\n"
                        + "FROM raw.orders o\n"
                        + "JOIN raw.customers c ON c.id = o.cust_id\n"
                        + "LEFT JOIN raw.products p ON p.id = o.prod_id");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).extracting(LineageEdge::downstreamUri).containsOnly("analytics.enriched");
        assertThat(edges).extracting(LineageEdge::upstreamUri)
                .containsExactlyInAnyOrder("raw.orders", "raw.customers", "raw.products");
        assertThat(edges).allSatisfy(e -> assertThat(e.relationshipType()).isEqualTo("CREATE_AS"));
    }

    @Test
    void mergeInto_using_yieldsSourceToTargetEdge() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query",
                "MERGE INTO warehouse.dim_customer t USING staging.customer_updates s "
                        + "ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.name = s.name");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("staging.customer_updates");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("warehouse.dim_customer");
        assertThat(edges.get(0).relationshipType()).isEqualTo("MERGE");
    }

    @Test
    void backtickedAndQualifiedIdentifiers_areNormalized() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query", "INSERT INTO `proj.mart.t` SELECT * FROM `proj.raw.s`");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("proj.raw.s");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("proj.mart.t");
    }

    @Test
    void selectOnly_hasNoWriteTarget_yieldsEmpty() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query", "SELECT a, b FROM raw.orders JOIN raw.customers USING (id)");

        assertThat(extractor.extract(event)).isEmpty();
    }

    @Test
    void insertValues_hasNoReadSource_yieldsEmpty() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query", "INSERT INTO mart.audit (id, msg) VALUES (1, 'hello')");

        assertThat(extractor.extract(event)).isEmpty();
    }

    @Test
    void insertSelectFromItself_isDroppedAsSelfLoop() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query", "INSERT INTO raw.orders SELECT * FROM raw.orders");

        assertThat(extractor.extract(event)).isEmpty();
    }

    @Test
    void structuredReferences_winOverSqlText() {
        // Event carries BOTH a BigQuery job structure AND raw SQL: the structured
        // branch (b) must take precedence, so the SQL parser never runs.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("referencedTables", List.of("projects/p/datasets/raw/tables/orders"));
        raw.put("destinationTable", "projects/p/datasets/mart/tables/orders_daily");
        raw.put("query", "INSERT INTO wrong.target SELECT * FROM wrong.source");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("rawEvent", raw);

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("projects/p/datasets/raw/tables/orders");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("projects/p/datasets/mart/tables/orders_daily");
    }

    @Test
    void sqlText_carriedUnderRawEvent_isParsed() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("statement", "INSERT OVERWRITE TABLE mart.f SELECT * FROM raw.e");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("operation", "hive-query");
        event.put("rawEvent", raw);

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("raw.e");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("mart.f");
        // event.operation overrides the parsed verb, upper-cased for consistent de-dup.
        assertThat(edges.get(0).relationshipType()).isEqualTo("HIVE-QUERY");
    }

    @Test
    void updateWithFrom_yieldsSourceToTargetEdge() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query",
                "UPDATE mart.dim_customer SET region = s.region FROM staging.geo s WHERE s.id = dim_customer.id");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamUri()).isEqualTo("staging.geo");
        assertThat(edges.get(0).downstreamUri()).isEqualTo("mart.dim_customer");
        assertThat(edges.get(0).relationshipType()).isEqualTo("UPDATE");
    }

    @Test
    void commentedOutFromClause_doesNotCreateFalseEdge() {
        // The real source is raw.orders; a line comment and a block comment each
        // mention a fake table that must NOT become an edge.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query",
                "INSERT INTO mart.t  -- FROM commented.fake\n"
                        + "SELECT * FROM raw.orders /* FROM another.fake */ WHERE x > 0");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).extracting(LineageEdge::upstreamUri).containsExactly("raw.orders");
        assertThat(edges).extracting(LineageEdge::downstreamUri).containsOnly("mart.t");
    }

    @Test
    void stringLiteralContainingFrom_doesNotCreateFalseEdge() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query",
                "INSERT INTO mart.t SELECT * FROM raw.orders WHERE note = 'copied FROM legacy.system'");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).extracting(LineageEdge::upstreamUri).containsExactly("raw.orders");
    }

    @Test
    void cteAliases_areNotTreatedAsSources() {
        // Only the real base table (raw.events) is lineage; the CTE alias "recent"
        // and its self-reference must be excluded.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query",
                "CREATE TABLE mart.daily AS\n"
                        + "WITH recent AS (SELECT * FROM raw.events WHERE ts > 0)\n"
                        + "SELECT day, count(*) FROM recent GROUP BY day");

        List<LineageEdge> edges = extractor.extract(event);

        assertThat(edges).extracting(LineageEdge::upstreamUri).containsExactly("raw.events");
        assertThat(edges).extracting(LineageEdge::downstreamUri).containsOnly("mart.daily");
    }

    @Test
    void insertOverwriteDirectory_writesToPath_yieldsEmpty() {
        // Hive: writes to a filesystem path, not a catalog table -> no asset lineage.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("query", "INSERT OVERWRITE DIRECTORY '/tmp/out' SELECT * FROM raw.orders");

        assertThat(extractor.extract(event)).isEmpty();
    }
}
