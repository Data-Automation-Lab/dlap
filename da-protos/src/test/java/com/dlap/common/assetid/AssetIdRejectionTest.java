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

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Forms that must not normalize, each with the reason a caller (and a quarantine record) sees. */
class AssetIdRejectionTest {

    private static final String SUB = "11111111-2222-3333-4444-555566667777";
    private static final String M = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    static Stream<Arguments> rejections() {
        AssetIdHints none = AssetIdHints.none();
        AssetIdHints unity = AssetIdHints.of(AssetProvider.UNITY);
        AssetIdHints unityM = AssetIdHints.builder().provider(AssetProvider.UNITY).metastoreId(M).build();
        return Stream.of(
                Arguments.of(null, none, Reason.BLANK),
                Arguments.of("", none, Reason.BLANK),
                Arguments.of("   ", none, Reason.BLANK),
                Arguments.of("unknown", none, Reason.UNRECOGNIZED),
                Arguments.of("3f2b8c1e-0d4a-4b6f-9a8e-1c2d3e4f5a6b", none, Reason.UNRECOGNIZED),
                Arguments.of("https://example.com/data.csv", none, Reason.UNRECOGNIZED),
                Arguments.of("unity:main.sales.orders", none, Reason.UNRECOGNIZED),
                Arguments.of("x", AssetIdHints.of(AssetProvider.OCI), Reason.UNRECOGNIZED),
                // Dotted names are BigQuery, Unity Catalog, Glue and Snowflake at once.
                Arguments.of("dlap-gcp-e2e.analytics.orders", none, Reason.AMBIGUOUS),
                Arguments.of("finance-reports", AssetIdHints.of(AssetProvider.AWS), Reason.AMBIGUOUS),
                Arguments.of("projects/dlap-gcp-e2e/instances/orders-db", none, Reason.AMBIGUOUS),
                Arguments.of("projects/dlap-gcp-e2e/locations/us/jobs/j1", none, Reason.AMBIGUOUS),
                // Hints the form needs.
                Arguments.of("main.sales.orders", unity, Reason.MISSING_HINT),
                Arguments.of("rds://us-east-1/prod-postgres", none, Reason.MISSING_HINT),
                Arguments.of("dynamodb://us-east-1/Orders", none, Reason.MISSING_HINT),
                Arguments.of("athena://us-east-1/query/a1b2c3d4", none, Reason.MISSING_HINT),
                Arguments.of("prod-postgres", AssetIdHints.of(AssetKind.RDS_INSTANCE), Reason.MISSING_HINT),
                Arguments.of("analytics", AssetIdHints.of(AssetKind.BIGQUERY_DATASET), Reason.MISSING_HINT),
                Arguments.of("analytics.orders", AssetIdHints.of(AssetProvider.GCP), Reason.MISSING_HINT),
                Arguments.of("azurecosmos://dlapcosmos/app/events", none, Reason.MISSING_HINT),
                Arguments.of("analytics", AssetIdHints.of(AssetKind.BLOB_CONTAINER), Reason.MISSING_HINT),
                Arguments.of("/Volumes/main/raw/landing", unity, Reason.MISSING_HINT),
                Arguments.of("databricks://dbc-a1b2c3d4-e5f6.cloud.databricks.com/jobs/runs/1", none, Reason.MISSING_HINT),
                Arguments.of("sales.public.orders", AssetIdHints.of(AssetProvider.SNOWFLAKE), Reason.MISSING_HINT),
                // Not assets.
                Arguments.of("arn:aws:iam::123456789012:user/alice", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("arn:aws:sts::123456789012:assumed-role/r/s", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("arn:aws:kms:us-east-1:123456789012:key/abc", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("arn:aws:s3:us-east-1:123456789012:accesspoint/ap1", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("arn:aws:rds:us-east-1:123456789012:cluster:aurora-1", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("arn:aws:dynamodb:us-east-1:123456789012:table/Orders/index/byDate", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("https://sqs.us-east-1.amazonaws.com/123456789012/q", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("projects/dlap-gcp-e2e/logs/cloudaudit.googleapis.com%2Factivity", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("projects/dlap-gcp-e2e/datasets/analytics/tables/events_*", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("/subscriptions/" + SUB, none, Reason.UNSUPPORTED_KIND),
                Arguments.of("/subscriptions/" + SUB + "/resourceGroups/rg-dlap-e2e/providers/Microsoft.Storage/storageAccounts/dlapazuree2e"
                        + "/blobServices/default/containers/analytics/providers/Microsoft.Authorization/roleAssignments/aa11bb22",
                        none, Reason.UNSUPPORTED_KIND),
                Arguments.of("/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm1", none,
                        Reason.UNSUPPORTED_KIND),
                // Stub crawler job forms (AzureProcessingJobResourceCrawler.java:164,183).
                Arguments.of("azure://data-factory/" + SUB + "/pipelines/p1", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("azure://synapse/" + SUB + "/sparkjobs/7", AssetIdHints.of(AssetKind.SPARK_JOB), Reason.UNSUPPORTED_KIND),
                // Without a hint the synapse form would read as a blob in an account named "synapse": never guess.
                Arguments.of("azure://synapse/" + SUB + "/sparkjobs/7", none, Reason.UNSUPPORTED_KIND),
                Arguments.of("azure://synapse/" + SUB.toUpperCase() + "/SparkJobs/7", AssetIdHints.of(AssetProvider.AZURE),
                        Reason.UNSUPPORTED_KIND),
                Arguments.of("/Volumes/main/raw/landing/2026/file.csv", unityM, Reason.UNSUPPORTED_KIND),
                // Malformed components.
                Arguments.of("s3://", none, Reason.MALFORMED),
                Arguments.of("s3://bad bucket/key", none, Reason.MALFORMED),
                Arguments.of("arn:aws:rds:us-east-1:null:db:prod", none, Reason.MALFORMED),
                Arguments.of("arn:aws:rds::123456789012:db:prod", none, Reason.MALFORMED),
                Arguments.of("arn:aws:rds", none, Reason.MALFORMED),
                Arguments.of("prod-postgres", AssetIdHints.builder().kind(AssetKind.RDS_INSTANCE).region("nowhere")
                        .account("123456789012").build(), Reason.MALFORMED),
                Arguments.of("prod-postgres", AssetIdHints.builder().kind(AssetKind.RDS_INSTANCE).region("us-east-1")
                        .account("12").build(), Reason.MALFORMED),
                Arguments.of("rds://us-east-1/prod-postgres", AssetIdHints.builder().account("null").build(), Reason.MALFORMED),
                Arguments.of("gs://a", none, Reason.MALFORMED),
                Arguments.of("https://storage.googleapis.com/bucket-ok/%ZZ", none, Reason.MALFORMED),
                Arguments.of("bq://dlap-gcp-e2e", none, Reason.MALFORMED),
                Arguments.of("bq://Bad_Project/d/t", none, Reason.MALFORMED),
                Arguments.of("az://Not-An-Account/c-1", none, Reason.MALFORMED),
                Arguments.of("az://acct1/Bad_Container/x", none, Reason.MALFORMED),
                Arguments.of("abfss://c-1@example.com/p", none, Reason.MALFORMED),
                Arguments.of("/subscriptions/not-a-guid/resourceGroups/rg/providers/Microsoft.Sql/servers/s/databases/d", none,
                        Reason.MALFORMED),
                Arguments.of("/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.DataFactory/factories/adf1/pipelines/ p1",
                        none, Reason.MALFORMED),
                Arguments.of("/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.DataFactory/factories/adf1/pipelines/p\u00011",
                        none, Reason.MALFORMED),
                Arguments.of("arn:aws:redshift:us-east-1:123456789012:cluster:1starts-with-digit", none, Reason.MALFORMED),
                Arguments.of("uc://" + M + "/main/sales/orders/extra", none, Reason.MALFORMED),
                Arguments.of("dbx://" + "123/runs/not-a-number", none, Reason.MALFORMED),
                Arguments.of("snowflake://acct/db/schema", none, Reason.MALFORMED),
                Arguments.of("s3://bucket-ok/key\0", none, Reason.MALFORMED),
                Arguments.of("x".repeat(AssetIdNormalizer.MAX_INPUT_LENGTH + 1), none, Reason.TOO_LONG),
                Arguments.of("s3://bucket-ok/" + "k".repeat(AssetIdNormalizer.MAX_URI_LENGTH), none, Reason.TOO_LONG));
    }

    @ParameterizedTest(name = "[{index}] {2}: {0}")
    @MethodSource("rejections")
    void rejectsWithTheExpectedReason(String raw, AssetIdHints hints, Reason expected) {
        AssetIdParseResult result = AssetIdNormalizer.parse(raw, hints);

        assertThat(result).isInstanceOf(AssetIdParseResult.Rejected.class);
        AssetIdParseResult.Rejected rejected = (AssetIdParseResult.Rejected) result;
        assertThat(rejected.reason()).as("detail: %s", rejected.detail()).isEqualTo(expected);
        assertThat(rejected.raw()).isEqualTo(raw);
        assertThat(rejected.detail()).isNotBlank();
        assertThat(result.id()).isEmpty();
        assertThat(AssetIdNormalizer.normalize(raw, hints)).isEmpty();
    }

    @Test
    void rejectionDetailsNeverEchoTheInput() {
        List<String> secrets = List.of(
                "s3://bad bucket/customer-ssn-123-45-6789.csv",
                "arn:aws:iam::123456789012:user/alice.smith@example.com",
                "projects/secret-project-77/logs/x");
        for (String raw : secrets) {
            AssetIdParseResult.Rejected r = (AssetIdParseResult.Rejected) AssetIdNormalizer.parse(raw, AssetIdHints.none());
            assertThat(r.detail()).doesNotContain("ssn").doesNotContain("alice").doesNotContain("secret-project");
        }
    }

    @Test
    void percentSignsInSchemeUrisArePartOfTheKey() {
        // Only http(s) URLs are percent-decoded; s3://, gs:// and az:// carry raw keys.
        assertThat(AssetIdNormalizer.canonicalUri("gs://bucket_ok/x%", null)).contains("gs://bucket_ok/x%");
        assertThat(AssetIdNormalizer.canonicalUri("s3://bucket-ok/a%20b", null)).contains("s3://bucket-ok/a%20b");
        assertThat(AssetIdNormalizer.canonicalUri("https://bucket-ok.s3.amazonaws.com/a%20b", null)).contains("s3://bucket-ok/a b");
    }

    @Test
    void sasTokensAreDroppedFromBlobUrls() {
        CanonicalAssetId id = AssetIdNormalizer.normalize(
                "https://acct1.blob.core.windows.net/c-1/a.csv?sv=2024-01-01&sig=SECRETSIGNATURE").orElseThrow();

        assertThat(id.uri()).isEqualTo("az://acct1/c-1/a.csv");
        assertThat(id.toString()).doesNotContain("SECRET");
        assertThat(id.attributes().values()).noneMatch(v -> v.contains("SECRET"));
    }

    @Test
    void buildRejectsPartsThatDoNotFormTheKind() {
        assertThat(AssetIdNormalizer.build(AssetKind.S3_BUCKET, List.of("bucket-ok/extra")).isNormalized()).isFalse();
        assertThat(AssetIdNormalizer.build(AssetKind.S3_OBJECT, List.of("bucket-ok", "")).isNormalized()).isFalse();
        assertThat(AssetIdNormalizer.build(AssetKind.UNITY_TABLE, List.of(M, "main", "sales/volumes", "x")).isNormalized()).isFalse();
        assertThat(AssetIdNormalizer.build(AssetKind.BLOB_OBJECT, List.of("acct1", "c-1/x", "p")).isNormalized()).isFalse();
        assertThat(AssetIdNormalizer.build(AssetKind.BIGQUERY_TABLE, List.of("p-12345", "d", "t/x")).isNormalized()).isFalse();
        assertThat(AssetIdNormalizer.build(AssetProvider.AWS, AssetKind.GLUE_TABLE,
                List.of("aws", "glue", "us-east-1", "123456789012", "database/sales"), null).isNormalized()).isFalse();
        assertThat(AssetIdNormalizer.build(AssetKind.SPARK_JOB, List.of("x"))).isInstanceOf(AssetIdParseResult.Rejected.class);
        assertThat(AssetIdNormalizer.build(AssetKind.DATA_LAKE, List.of("x")))
                .extracting(r -> ((AssetIdParseResult.Rejected) r).reason()).isEqualTo(Reason.UNSUPPORTED_KIND);
        assertThat(AssetIdNormalizer.build(AssetProvider.GCP, AssetKind.S3_BUCKET, List.of("bucket-ok"), null).isNormalized()).isFalse();
        assertThat(AssetIdNormalizer.build(AssetKind.S3_BUCKET, java.util.Arrays.asList((String) null)).isNormalized()).isFalse();
    }
}
