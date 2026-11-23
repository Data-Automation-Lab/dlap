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

package com.dlap.common.assetid;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.assetid.AssetIdParseResult.Reason;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Mapping to and from OpenLineage dataset names (https://openlineage.io/docs/spec/naming,
 * page version 1.54.0, read 2026-10-18).
 */
class OpenLineageNamingTest {

    private static final String M = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final String HOST = "adb-1234567890123456.7.azuredatabricks.net";
    private static final AssetIdHints UC = AssetIdHints.builder().metastoreId(M).build();

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of("s3://b-1/dir/k.csv", "s3://b-1", "dir/k.csv"),
                Arguments.of("s3://b-1", "s3://b-1", "/"),
                Arguments.of("gs://b-1/dir/o.parquet", "gs://b-1", "dir/o.parquet"),
                Arguments.of("gs://b-1", "gs://b-1", "/"),
                Arguments.of("az://acct1/c-1/dir/x.parquet", "abfss://c-1@acct1.dfs.core.windows.net", "dir/x.parquet"),
                Arguments.of("az://acct1/c-1", "abfss://c-1@acct1.dfs.core.windows.net", "/"),
                Arguments.of("bq://p-12345/sales/orders", "bigquery", "p-12345.sales.orders"),
                Arguments.of("bq://example.com:p-12345/sales/orders", "bigquery", "example.com:p-12345.sales.orders"),
                Arguments.of("arn:aws:glue:us-east-1:123456789012:table/sales/orders",
                        "arn:aws:glue:us-east-1:123456789012", "table/sales/orders"),
                Arguments.of("snowflake://myorg-analytics/SALES/PUBLIC/ORDERS", "snowflake://myorg-analytics", "SALES.PUBLIC.ORDERS"),
                Arguments.of("uc://" + M + "/main/sales/orders", "unitycatalog://" + HOST, "main.sales.orders"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mappings")
    void mapsToTheNamingConventionAndBack(String uri, String namespace, String name) {
        CanonicalAssetId id = AssetIdNormalizer.normalize(uri).orElseThrow();

        OpenLineageDataset ds = OpenLineageNaming.toDataset(id, HOST).orElseThrow();

        assertThat(ds).isEqualTo(new OpenLineageDataset(namespace, name));
        assertThat(OpenLineageNaming.fromDataset(ds.namespace(), ds.name(), UC).id()).contains(id);
    }

    @Test
    void kindsWithoutADatasetFormMapToEmpty() {
        for (String uri : new String[] {"bqjob://p-12345/j1", "bq://p-12345/d", "az://acct1",
                "dbx://1234567890123456/runs/7", "arn:aws:rds:us-east-1:123456789012:db:x1"}) {
            assertThat(OpenLineageNaming.toDataset(AssetIdNormalizer.normalize(uri).orElseThrow())).as(uri).isEmpty();
        }
        assertThat(OpenLineageNaming.toDataset(AssetIdNormalizer.normalize("uc://" + M + "/main/sales/orders").orElseThrow()))
                .as("a Unity Catalog table needs the workspace host").isEmpty();
    }

    @Test
    void inboundVariantsNormalize() {
        assertThat(OpenLineageNaming.fromDataset("wasbs://c-1@acct1.blob.core.windows.net", "x.csv", null).id())
                .map(CanonicalAssetId::uri).contains("az://acct1/c-1/x.csv");
        assertThat(OpenLineageNaming.fromDataset("snowflake://myorg-analytics", "SALES.PUBLIC.\"MixedCase\"", null).id())
                .map(CanonicalAssetId::uri).contains("snowflake://myorg-analytics/SALES/PUBLIC/MixedCase");
        assertThat(OpenLineageNaming.fromDataset("bigquery", "p-12345.sales.orders", null).id())
                .map(CanonicalAssetId::uri).contains("bq://p-12345/sales/orders");
    }

    @Test
    void unmappableDatasetsAreRejected() {
        assertThat(reason(OpenLineageNaming.fromDataset("unitycatalog://" + HOST, "main.sales.orders", null)))
                .isEqualTo(Reason.MISSING_HINT);
        assertThat(reason(OpenLineageNaming.fromDataset("postgres://db.example.com:5432", "app.public.users", null)))
                .isEqualTo(Reason.UNRECOGNIZED);
        assertThat(reason(OpenLineageNaming.fromDataset("arn:aws:rds:us-east-1:123456789012", "db:x1", null)))
                .isEqualTo(Reason.UNSUPPORTED_KIND);
        assertThat(reason(OpenLineageNaming.fromDataset("snowflake://acct", "only.two", null))).isEqualTo(Reason.MALFORMED);
        assertThat(reason(OpenLineageNaming.fromDataset("abfss://c-1@example.com", "x", null))).isEqualTo(Reason.MALFORMED);
        assertThat(reason(OpenLineageNaming.fromDataset(" ", "x", null))).isEqualTo(Reason.BLANK);
        assertThat(reason(OpenLineageNaming.fromDataset("s3://b-1", null, null))).isEqualTo(Reason.BLANK);
    }

    private static Reason reason(AssetIdParseResult r) {
        return ((AssetIdParseResult.Rejected) r).reason();
    }
}
