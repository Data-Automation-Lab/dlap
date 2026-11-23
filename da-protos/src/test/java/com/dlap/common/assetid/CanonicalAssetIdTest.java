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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.assetid.CanonicalAssetId.Attribute;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Parent roll-up, equality, attributes and the build API. */
class CanonicalAssetIdTest {

    private static final String M = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final String SUB = "11111111-2222-3333-4444-555566667777";

    private static CanonicalAssetId id(String raw) {
        return AssetIdNormalizer.normalize(raw).orElseThrow(() -> new AssertionError("did not normalize: " + raw));
    }

    private static List<String> chain(CanonicalAssetId start) {
        List<String> out = new ArrayList<>();
        Optional<CanonicalAssetId> cur = Optional.of(start);
        while (cur.isPresent()) {
            out.add(cur.get().uri());
            cur = cur.get().parent();
        }
        return out;
    }

    static Stream<Arguments> parentChains() {
        return Stream.of(
                Arguments.of("s3://b-1/dir/k.csv", List.of("s3://b-1/dir/k.csv", "s3://b-1")),
                Arguments.of("gs://b-1/dir/o", List.of("gs://b-1/dir/o", "gs://b-1")),
                Arguments.of("bq://p-12345/d/t", List.of("bq://p-12345/d/t", "bq://p-12345/d")),
                Arguments.of("arn:aws:glue:us-east-1:123456789012:table/sales/orders",
                        List.of("arn:aws:glue:us-east-1:123456789012:table/sales/orders",
                                "arn:aws:glue:us-east-1:123456789012:database/sales")),
                Arguments.of("az://acct1/c-1/dir/x.parquet", List.of("az://acct1/c-1/dir/x.parquet", "az://acct1/c-1", "az://acct1")),
                Arguments.of("uc://" + M + "/main/sales/orders",
                        List.of("uc://" + M + "/main/sales/orders", "uc://" + M + "/main/sales", "uc://" + M + "/main")),
                Arguments.of("uc://" + M + "/main/raw/volumes/landing",
                        List.of("uc://" + M + "/main/raw/volumes/landing", "uc://" + M + "/main/raw", "uc://" + M + "/main")),
                Arguments.of("dbx://1234567890123456/jobs/42/runs/987",
                        List.of("dbx://1234567890123456/runs/987", "dbx://1234567890123456/jobs/42")),
                Arguments.of("dbx://1234567890123456/runs/987", List.of("dbx://1234567890123456/runs/987")),
                Arguments.of("s3://b-1", List.of("s3://b-1")),
                Arguments.of("bqjob://p-12345/j1", List.of("bqjob://p-12345/j1")),
                Arguments.of("snowflake://acct/DB/S/T", List.of("snowflake://acct/DB/S/T")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parentChains")
    void parentRollsUpOneLevelAtATime(String raw, List<String> expected) {
        CanonicalAssetId start = id(raw);

        assertThat(chain(start)).containsExactlyElementsOf(expected);
        start.parent().ifPresent(p -> {
            if (start.kind() == AssetKind.DATABRICKS_JOB) {
                // A run's parent is its job: same kind, one level up.
                assertThat(p.kind()).isEqualTo(AssetKind.DATABRICKS_JOB);
            } else {
                assertThat(start.kind().parentKind()).contains(p.kind());
            }
            assertThat(AssetIdNormalizer.isCanonical(p.uri())).isTrue();
            assertThat(p.provider()).isEqualTo(start.provider());
        });
    }

    @Test
    void parentKindsMatchTheParentIds() {
        for (AssetKind k : AssetKind.values()) {
            k.parentKind().ifPresent(p -> assertThat(p.defaultProvider()).isEqualTo(k.defaultProvider()));
        }
    }

    @Test
    void objectsRollUpToTheirContainerAndOthersToThemselves() {
        assertThat(id("s3://b-1/k").rollUp().uri()).isEqualTo("s3://b-1");
        assertThat(id("gs://b-1/k").rollUp().uri()).isEqualTo("gs://b-1");
        assertThat(id("az://acct1/c-1/k").rollUp().uri()).isEqualTo("az://acct1/c-1");
        CanonicalAssetId table = id("bq://p-12345/d/t");
        assertThat(table.rollUp()).isSameAs(table);
        assertThat(id("s3://b-1/k").isObjectLevel()).isTrue();
        assertThat(table.isObjectLevel()).isFalse();
    }

    @Test
    void equalityIsUriEqualityAndIgnoresAttributes() {
        CanonicalAssetId fromArn = id("arn:aws:s3:::b-1");
        CanonicalAssetId fromUri = id("s3://b-1");

        assertThat(fromArn).isEqualTo(fromUri).hasSameHashCodeAs(fromUri);
        assertThat(fromArn.attributes()).containsEntry(Attribute.PARTITION, "aws");
        assertThat(fromUri.attributes()).isEmpty();
        assertThat(fromArn.toString()).isEqualTo("s3://b-1");
        assertThat(fromArn).isNotEqualTo("s3://b-1");
    }

    @Test
    void attributesCarryNonIdentityFacts() {
        assertThat(id("projects/dlap-gcp-e2e/datasets/d/tables/t$20260701").attributes())
                .containsEntry(Attribute.DECORATOR, "$20260701");
        assertThat(id("bqjob://dlap-gcp-e2e/EU/j1").attribute(Attribute.LOCATION)).contains("EU");
        assertThat(id("projects/my-project-1/buckets/b-1").attribute(Attribute.PROJECT)).contains("my-project-1");
        CanonicalAssetId container = id("/subscriptions/" + SUB
                + "/resourceGroups/RG-1/providers/Microsoft.Storage/storageAccounts/acct1/blobServices/default/containers/c-1");
        assertThat(container.uri()).isEqualTo("az://acct1/c-1");
        assertThat(container.attributes()).containsEntry(Attribute.SUBSCRIPTION, SUB).containsEntry(Attribute.RESOURCE_GROUP, "rg-1");
        CanonicalAssetId rds = id("arn:aws:rds:eu-west-1:123456789012:db:x1");
        assertThat(rds.attributes()).containsEntry(Attribute.REGION, "eu-west-1").containsEntry(Attribute.ACCOUNT, "123456789012");
        assertThat(id("dbx://1234567890123456/jobs/42/runs/987").attribute(Attribute.JOB_ID)).contains("42");
    }

    @Test
    void partsAndAttributesAreImmutable() {
        CanonicalAssetId arn = id("arn:aws:s3:::b-1/k");
        assertThatThrownBy(() -> arn.parts().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> arn.attributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void kindHintRefinesAGlueJob() {
        String arn = "arn:aws:glue:us-east-1:123456789012:job/shell-job";

        assertThat(AssetIdNormalizer.normalize(arn).orElseThrow().kind()).isEqualTo(AssetKind.SPARK_JOB);
        CanonicalAssetId python = AssetIdNormalizer.normalize(arn, AssetIdHints.of(AssetKind.PYTHON_JOB)).orElseThrow();
        assertThat(python.kind()).isEqualTo(AssetKind.PYTHON_JOB);
        assertThat(python.uri()).isEqualTo(arn);
        assertThat(AssetIdNormalizer.build(AssetProvider.AWS, AssetKind.PYTHON_JOB, python.parts(), null).id())
                .map(CanonicalAssetId::kind).contains(AssetKind.PYTHON_JOB);
    }

    @Test
    void selfDescribingFormsIgnoreAConflictingProviderHint() {
        CanonicalAssetId id = AssetIdNormalizer.normalize("gs://b-1", AssetIdHints.of(AssetProvider.AWS)).orElseThrow();

        assertThat(id.provider()).isEqualTo(AssetProvider.GCP);
        assertThat(id.kind()).isEqualTo(AssetKind.GCS_BUCKET);
    }

    @Test
    void buildCreatesIdsFromStructuredParts() {
        assertThat(AssetIdNormalizer.build(AssetKind.S3_OBJECT, List.of("b-1", "dir/a b.csv")).id())
                .map(CanonicalAssetId::uri).contains("s3://b-1/dir/a b.csv");
        assertThat(AssetIdNormalizer.build(AssetKind.BIGQUERY_TABLE, List.of("P-12345", "Sales", "Orders")).id())
                .map(CanonicalAssetId::uri).contains("bq://p-12345/Sales/Orders");
        assertThat(AssetIdNormalizer.build(AssetKind.UNITY_TABLE, List.of(M, "Main", "Sales", "Orders")).id())
                .map(CanonicalAssetId::uri).contains("uc://" + M + "/main/sales/orders");
        assertThat(AssetIdNormalizer.build(AssetKind.BLOB_OBJECT, List.of("ACCT1", "c-1", "Dir/X.csv")).id())
                .map(CanonicalAssetId::uri).contains("az://acct1/c-1/Dir/X.csv");
        assertThat(AssetIdNormalizer.build(AssetProvider.AZURE, AssetKind.DATAFLOW_JOB, List.of(SUB, "RG-1", "adf1", "p1"), null).id())
                .map(CanonicalAssetId::uri)
                .contains("/subscriptions/" + SUB + "/resourcegroups/rg-1/providers/microsoft.datafactory/factories/adf1/pipelines/p1");
        assertThat(AssetIdNormalizer.build(AssetKind.REDSHIFT_QUERY, List.of("us-east-1", "123456789012", "q-1")).id())
                .map(CanonicalAssetId::uri).contains("athena://us-east-1/123456789012/q-1");
        assertThat(AssetIdNormalizer.build(AssetKind.DATABRICKS_JOB, List.of("1234567890123456", "runs", "7")).id())
                .map(CanonicalAssetId::uri).contains("dbx://1234567890123456/runs/7");
    }

    @Test
    void hintsTreatBlankAsAbsentAndBuilderRoundTrips() {
        AssetIdHints h = AssetIdHints.builder().provider(AssetProvider.GCP).kind(AssetKind.BIGQUERY_TABLE).account("  ")
                .project(" p-12345 ").region("").subscription(null).resourceGroup("rg").metastoreId("m").workspaceId("1")
                .build();

        assertThat(h.account()).isNull();
        assertThat(h.project()).isEqualTo("p-12345");
        assertThat(h.region()).isNull();
        assertThat(h.toBuilder().build()).isEqualTo(h);
        assertThat(h.withKind(null).kind()).isNull();
        assertThat(h.withProvider(AssetProvider.AWS).provider()).isEqualTo(AssetProvider.AWS);
        assertThat(AssetIdHints.builder().serviceType("bigquery_table").build().kind()).isEqualTo(AssetKind.BIGQUERY_TABLE);
        assertThat(AssetIdHints.builder().serviceType("NOT_A_TYPE").build().kind()).isNull();
        assertThat(AssetIdHints.none().effectiveProvider()).isNull();
        assertThat(AssetIdHints.of(AssetKind.GCS_BUCKET).effectiveProvider()).isEqualTo(AssetProvider.GCP);
        assertThat(AssetIdHints.of(AssetKind.SPARK_JOB).effectiveProvider()).isNull();
    }

    @Test
    void nullHintsMeanNone() {
        assertThat(AssetIdNormalizer.normalize("s3://b-1", null)).isPresent();
        assertThat(AssetIdNormalizer.parse("b-1", null).isNormalized()).isFalse();
    }
}
