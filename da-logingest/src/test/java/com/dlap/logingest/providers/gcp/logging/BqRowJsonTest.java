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

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.gcpaudit.GcpAuditFormat;
import com.dlap.logingest.parse.gcpaudit.GcpAuditRegistryForTests;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.LegacySQLTypeName;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BigQuery result rows to {@code bq-sink-row} JSON: a row shaped like the sink's
 * {@code cloudaudit_googleapis_com_data_access} table schema converts and parses.
 */
class BqRowJsonTest {

    private static FieldValue v(String s) {
        return FieldValue.of(FieldValue.Attribute.PRIMITIVE, s);
    }

    private static FieldValue rec(FieldList fields, FieldValue... values) {
        return FieldValue.of(FieldValue.Attribute.RECORD, FieldValueList.of(List.of(values), fields));
    }

    @Test
    void scalarsRecordsRepeatedAndNullsConvertAsTheEnvelopeExpects() {
        FieldList labels = FieldList.of(Field.of("project_id", LegacySQLTypeName.STRING),
                Field.of("bucket_name", LegacySQLTypeName.STRING));
        FieldList resource = FieldList.of(Field.of("type", LegacySQLTypeName.STRING),
                Field.of("labels", LegacySQLTypeName.RECORD, labels));
        FieldList auth = FieldList.of(Field.of("principalEmail", LegacySQLTypeName.STRING));
        FieldList authz = FieldList.of(Field.of("permission", LegacySQLTypeName.STRING),
                Field.of("granted", LegacySQLTypeName.BOOLEAN));
        FieldList payload = FieldList.of(Field.of("serviceName", LegacySQLTypeName.STRING),
                Field.of("methodName", LegacySQLTypeName.STRING), Field.of("resourceName", LegacySQLTypeName.STRING),
                Field.of("numResponseItems", LegacySQLTypeName.INTEGER),
                Field.of("authenticationInfo", LegacySQLTypeName.RECORD, auth),
                Field.newBuilder("authorizationInfo", LegacySQLTypeName.RECORD, authz).setMode(Field.Mode.REPEATED).build(),
                Field.of("metadataJson", LegacySQLTypeName.STRING));
        FieldList schema = FieldList.of(Field.of("logName", LegacySQLTypeName.STRING),
                Field.of("resource", LegacySQLTypeName.RECORD, resource),
                Field.of("protopayload_auditlog", LegacySQLTypeName.RECORD, payload),
                Field.of("timestamp", LegacySQLTypeName.TIMESTAMP),
                Field.of("receiveTimestamp", LegacySQLTypeName.TIMESTAMP),
                Field.of("severity", LegacySQLTypeName.STRING),
                Field.of("insertId", LegacySQLTypeName.STRING),
                Field.of("trace", LegacySQLTypeName.STRING),
                Field.of("score", LegacySQLTypeName.FLOAT),
                Field.of("blob", LegacySQLTypeName.BYTES));
        long seconds = Instant.parse("2026-09-01T10:00:15Z").getEpochSecond();
        FieldValueList row = FieldValueList.of(List.of(
                v("projects/proj-0d1a0001/logs/cloudaudit.googleapis.com%2Fdata_access"),
                rec(resource, v("gcs_bucket"), rec(labels, v("proj-0d1a0001"), v("dlap-fixture-bucket"))),
                rec(payload, v("storage.googleapis.com"), v("storage.objects.get"),
                        v("projects/_/buckets/dlap-fixture-bucket/objects/exports/orders.csv"), v("3"),
                        rec(auth, v("analyst@example.com")),
                        FieldValue.of(FieldValue.Attribute.REPEATED, List.of(
                                rec(authz, v("storage.objects.get"), v("true")))),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, null)),
                v(seconds + ".123456"), v((seconds + 1) + ".5"), v("INFO"), v("bq-row-1"),
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, null), v("0.25"), v("AAEC")), schema);

        ObjectNode json = BqRowJson.row(schema, row);

        assertThat(json.path("timestamp").asText()).isEqualTo("2026-09-01T10:00:15.123456Z");
        assertThat(json.path("receiveTimestamp").asText()).isEqualTo("2026-09-01T10:00:16.500Z");
        assertThat(json.path("protopayload_auditlog").path("numResponseItems").isIntegralNumber()).isTrue();
        assertThat(json.path("protopayload_auditlog").path("authorizationInfo").get(0).path("granted").isBoolean()).isTrue();
        assertThat(json.path("protopayload_auditlog").has("metadataJson")).as("a NULL column is left out").isFalse();
        assertThat(json.has("trace")).isFalse();
        assertThat(json.path("score").asDouble()).isEqualTo(0.25);
        assertThat(json.path("resource").path("labels").path("project_id").asText()).isEqualTo("proj-0d1a0001");

        RawRecord r = RawRecord.builder().bindingId("gcp-test").sourceKind(GcpAuditFormat.SOURCE_KIND)
                .envelope(GcpAuditFormat.BQ_SINK_ROW).ref(RawRef.of(BqSinkTransport.ID, "bq://test", 0))
                .fetchedAt(GcpTransportsForTests.NOW).json(json).build();
        ParseOutcome o = GcpAuditRegistryForTests.registry().parse(r, BindingConfig.auto("gcp-test", GcpAuditFormat.SOURCE_KIND));
        assertThat(o.outcomeCode()).startsWith("PARSED");
        CanonicalEvent e = ((ParseResult.Parsed) o.result()).events().get(0);
        assertThat(e.service()).isEqualTo("storage.googleapis.com");
        assertThat(e.operation()).isEqualTo("storage.objects.get");
        assertThat(e.eventTime()).isEqualTo(Instant.parse("2026-09-01T10:00:15.123456Z"));
        assertThat(e.target().canonicalUri()).isEqualTo("gs://dlap-fixture-bucket/exports/orders.csv");
    }
}
