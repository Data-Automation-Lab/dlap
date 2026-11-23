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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.StandardSQLTypeName;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parts of the BigQuery gateway that need no network: the SQL text and what may enter it, the
 * conversion of a result row to JSON, and the sorting of BigQuery failures.
 */
class BigQueryGatewayPartsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------
    // SQL
    // ------------------------------------------------------------------

    @Test
    void theViewIsQualifiedByProjectAndLowerCasedRegion() {
        assertThat(BqJobsQueries.view("proj-x", "US")).isEqualTo("`proj-x`.`region-us`.INFORMATION_SCHEMA.JOBS_BY_PROJECT");
        assertThat(BqJobsQueries.view("example.com:proj", "europe-west3"))
                .isEqualTo("`example.com:proj`.`region-europe-west3`.INFORMATION_SCHEMA.JOBS_BY_PROJECT");
    }

    @Test
    void projectsLocationsAndColumnsAreValidatedBeforeTheyBecomeSql() {
        assertThatThrownBy(() -> BqJobsQueries.view("p`x", "US")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BqJobsQueries.view("p", "US.`x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BqJobsQueries.view("", "US")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BqJobsQueries.rowsSql("p", "US", List.of("job_id", "x; DROP"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BqJobsQueries.rowsSql("p", "US", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRowsQueryUsesNamedParametersForEveryTimeBound() {
        String sql = BqJobsQueries.rowsSql("p", "US", List.of("job_id", "end_time"));
        assertThat(sql).contains("@creation_floor", "@from", "@to");
        assertThat(sql).doesNotContain("CURRENT_TIMESTAMP");
        assertThat(sql).startsWith("SELECT j.job_id, j.end_time\nFROM ");
    }

    // ------------------------------------------------------------------
    // Row conversion
    // ------------------------------------------------------------------

    @Test
    void aRowBecomesAJsonObjectWithNestedRecordsArraysAndTimestamps() {
        FieldList table = FieldList.of(
                Field.of("project_id", StandardSQLTypeName.STRING),
                Field.of("dataset_id", StandardSQLTypeName.STRING),
                Field.of("table_id", StandardSQLTypeName.STRING));
        FieldList fields = FieldList.of(
                Field.of("job_id", StandardSQLTypeName.STRING),
                Field.of("end_time", StandardSQLTypeName.TIMESTAMP),
                Field.of("cache_hit", StandardSQLTypeName.BOOL),
                Field.of("total_bytes_billed", StandardSQLTypeName.INT64),
                Field.of("error_result", StandardSQLTypeName.STRUCT, FieldList.of(Field.of("reason", StandardSQLTypeName.STRING))),
                Field.newBuilder("referenced_tables", StandardSQLTypeName.STRUCT, table).setMode(Field.Mode.REPEATED).build(),
                Field.of("parent_job_id", StandardSQLTypeName.STRING));
        FieldValue tableValue = FieldValue.of(FieldValue.Attribute.RECORD, FieldValueList.of(List.of(
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, "p"),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, "d"),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, "t")), table));
        FieldValueList row = FieldValueList.of(List.of(
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, "job_1"),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, "1.7894784E9"),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, "false"),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, "10485760"),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, null),
                FieldValue.of(FieldValue.Attribute.REPEATED, List.of(tableValue)),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, null)), fields);

        ObjectNode json = BigQueryRowJson.toJson(fields, row);

        assertThat(json.get("job_id").asText()).isEqualTo("job_1");
        assertThat(json.get("end_time").asText()).isEqualTo("2026-09-15T13:20:00Z");
        assertThat(json.get("cache_hit").isBoolean()).isTrue();
        assertThat(json.get("cache_hit").asBoolean()).isFalse();
        assertThat(json.get("total_bytes_billed").asLong()).isEqualTo(10485760L);
        assertThat(json.get("error_result").isNull()).as("a null stays a null, so a missing value and a missing column differ").isTrue();
        assertThat(json.has("parent_job_id")).isTrue();
        assertThat(json.get("parent_job_id").isNull()).isTrue();
        JsonNode first = json.get("referenced_tables").get(0);
        assertThat(first.get("project_id").asText()).isEqualTo("p");
        assertThat(first.get("dataset_id").asText()).isEqualTo("d");
        assertThat(first.get("table_id").asText()).isEqualTo("t");
    }

    @Test
    void aTimestampKeepsTheMicrosecondsBigQueryHolds() {
        FieldList fields = FieldList.of(Field.of("end_time", StandardSQLTypeName.TIMESTAMP));
        FieldValueList row = FieldValueList.of(List.of(FieldValue.of(FieldValue.Attribute.PRIMITIVE, "1.7894784003E9")), fields);

        assertThat(BigQueryRowJson.toJson(fields, row).get("end_time").asText()).isEqualTo("2026-09-15T13:20:00.300Z");
    }

    // ------------------------------------------------------------------
    // Failure classification
    // ------------------------------------------------------------------

    private static BigQueryException failure(int code, String reason, String message) {
        return new BigQueryException(code, message, new BigQueryError(reason, "location", message));
    }

    @Test
    void failuresAreSortedIntoTheKindsTheTransportActsOn() {
        assertThat(BigQueryJobsViewGateway.classify(failure(403, "accessDenied", "Access Denied: Project p: User does not have bigquery.jobs.create permission")).kind())
                .isEqualTo(JobsViewGateway.ViewFailure.Kind.PERMISSION_DENIED);
        assertThat(BigQueryJobsViewGateway.classify(failure(404, "notFound", "Not found: Dataset p:region-eu was not found in location US")).kind())
                .isEqualTo(JobsViewGateway.ViewFailure.Kind.LOCATION_MISMATCH);
        assertThat(BigQueryJobsViewGateway.classify(failure(400, "invalidQuery", "Syntax error")).kind())
                .isEqualTo(JobsViewGateway.ViewFailure.Kind.OTHER);
        assertThat(BigQueryJobsViewGateway.classify(failure(500, "backendError", "oops")).kind())
                .isEqualTo(JobsViewGateway.ViewFailure.Kind.OTHER);
    }

    @Test
    void aFailureMessageNeverRepeatsTheProvidersTextOrTheSql() {
        JobsViewGateway.ViewFailure f = BigQueryJobsViewGateway.classify(
                failure(400, "invalidQuery", "Unrecognized name: secret_marker at SELECT secret_marker FROM t"));

        assertThat(f.getMessage()).doesNotContain("secret_marker").contains("400").contains("invalidQuery");
    }

    @Test
    void theGatewayIsBuiltWithoutLookingForCredentials() {
        // Creating the bean must not call Application Default Credentials: a service without BigQuery
        // bindings runs on machines that have none. Clients are built on first use.
        assertThat(new BigQueryJobsViewGateway()).isNotNull();
    }
}
