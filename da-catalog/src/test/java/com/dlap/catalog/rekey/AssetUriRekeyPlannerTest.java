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

package com.dlap.catalog.rekey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.dlap.catalog.rekey.RekeyPlan.MergeGroup;
import com.dlap.catalog.rekey.RekeyPlan.StoredAsset;
import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;

/** The planner's grouping and classification, with the real normalizer and the matcher's row hints. */
class AssetUriRekeyPlannerTest {

    /** The hints {@code CatalogEventDecoder.normalizeStored} derives from a row, without default hints. */
    private static final Function<StoredAsset, AssetIdParseResult> NORMALIZE = row -> {
        AssetIdHints.Builder b = AssetIdHints.builder();
        AssetProvider.fromString(row.cloudProvider()).ifPresent(b::provider);
        AssetKind.fromServiceType(row.serviceType()).ifPresent(b::kind);
        b.region(row.region());
        return AssetIdNormalizer.parse(row.assetUri(), b.build());
    };

    @Test
    void rowsThatNormalizeToOneUriFormAGroupWhoseFirstRowSurvives() {
        StoredAsset oldest = row(1, "bkt1", "GCS_BUCKET", "GCP", "2026-01-01");
        StoredAsset canonical = row(2, "gs://bkt1", "GCS_BUCKET", "GCP", "2026-02-01");
        StoredAsset audit = row(3, "projects/_/buckets/bkt1", "GCS_BUCKET", "GCP", "2026-03-01");

        RekeyPlan plan = AssetUriRekeyPlanner.plan(List.of(oldest, canonical, audit), NORMALIZE);

        assertThat(plan.groups()).hasSize(1);
        MergeGroup g = plan.groups().get(0);
        assertThat(g.target().uri()).isEqualTo("gs://bkt1");
        assertThat(g.survivor()).isEqualTo(oldest);
        assertThat(g.merged()).containsExactly(canonical, audit);
        assertThat(g.kindConflict()).isFalse();
        assertThat(plan.mergedRows()).isEqualTo(2);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.oldUris()).containsExactlyInAnyOrder("bkt1", "gs://bkt1", "projects/_/buckets/bkt1");
        assertThat(plan.isEmpty()).isFalse();
    }

    @Test
    void aLoneRowIsReKeyedInPlaceAndACanonicalRowIsLeftAlone() {
        StoredAsset legacyTable = row(1, "p0000a:d.t", "BIGQUERY_TABLE", "GCP", "2026-01-01");
        StoredAsset canonical = row(2, "s3://bucket-ok", "S3_BUCKET", "AWS", "2026-01-02");

        RekeyPlan plan = AssetUriRekeyPlanner.plan(List.of(legacyTable, canonical), NORMALIZE);

        assertThat(plan.unchanged()).isEqualTo(1);
        assertThat(plan.changes()).singleElement().satisfies(c -> {
            assertThat(c.row()).isEqualTo(legacyTable);
            assertThat(c.target().uri()).isEqualTo("bq://p0000a/d/t");
            assertThat(c.kindChange()).isFalse();
        });
        assertThat(plan.oldUris()).containsExactly("p0000a:d.t");
        assertThat(plan.scanned()).isEqualTo(2);
    }

    @Test
    void aCanonicalUriWithTheWrongTypeIsAKindChange() {
        StoredAsset mistyped = row(1, "s3://bucket-mistyped", "GCS_BUCKET", "GCP", "2026-01-01");

        RekeyPlan plan = AssetUriRekeyPlanner.plan(List.of(mistyped), NORMALIZE);

        assertThat(plan.changes()).singleElement().satisfies(c -> {
            assertThat(c.kindChange()).isTrue();
            assertThat(c.target().serviceType()).isEqualTo("S3_BUCKET");
            assertThat(c.target().cloudProvider()).isEqualTo("AWS");
        });
        assertThat(plan.oldUris()).as("the URI itself does not change").isEmpty();
    }

    @Test
    void aRowThatDoesNotNormalizeIsRejectedWithItsReasonAndNotGrouped() {
        StoredAsset bad = row(1, "not a uri", "DATA_LAKE", "AZURE", "2026-01-01");

        RekeyPlan plan = AssetUriRekeyPlanner.plan(List.of(bad), NORMALIZE);

        assertThat(plan.rejected()).singleElement().satisfies(r -> {
            assertThat(r.row()).isEqualTo(bad);
            assertThat(r.reason()).isNotBlank();
        });
        assertThat(plan.isEmpty()).as("nothing to change").isTrue();
    }

    @Test
    void membersStoredWithDifferentTypesAreAKindConflictAndTheSurvivorsKindWins() {
        String arn = "arn:aws:glue:eu-west-1:123456789012:job/nightly";
        StoredAsset python = row(1, arn, "PYTHON_JOB", "AWS", "2026-01-01");
        // The planner does not enforce the unique index; one ARN read with two kind hints is the case.
        StoredAsset spark = row(2, arn, "SPARK_JOB", "AWS", "2026-01-02");

        RekeyPlan plan = AssetUriRekeyPlanner.plan(List.of(python, spark), NORMALIZE);

        assertThat(plan.groups()).singleElement().satisfies(g -> {
            assertThat(g.kindConflict()).isTrue();
            assertThat(g.target().serviceType()).isEqualTo("PYTHON_JOB");
            assertThat(g.members()).containsExactly(python, spark);
        });
    }

    @Test
    void theNativeCandidateIsTheFirstNativeFormSeenOldestFirst() {
        StoredAsset canonicalNoNative = row(1, "gs://bkt2", "GCS_BUCKET", "GCP", "2026-01-01");
        StoredAsset bare = row(2, "bkt2", "GCS_BUCKET", "GCP", "2026-01-02");
        RekeyPlan plan = AssetUriRekeyPlanner.plan(List.of(canonicalNoNative, bare), NORMALIZE);
        assertThat(AssetUriRekeyExecutor.nativeCandidate(plan.groups().get(0))).isEqualTo("bkt2");

        StoredAsset withNative = new StoredAsset(id(3), "gs://bkt3", "GCS_BUCKET", "GCP", null, "projects/_/buckets/bkt3",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        StoredAsset bare3 = row(4, "bkt3", "GCS_BUCKET", "GCP", "2026-01-02");
        RekeyPlan plan3 = AssetUriRekeyPlanner.plan(List.of(withNative, bare3), NORMALIZE);
        assertThat(AssetUriRekeyExecutor.nativeCandidate(plan3.groups().get(0))).isEqualTo("projects/_/buckets/bkt3");

        StoredAsset c1 = row(5, "gs://bkt4", "GCS_BUCKET", "GCP", "2026-01-01");
        RekeyPlan same = AssetUriRekeyPlanner.plan(List.of(c1), NORMALIZE);
        assertThat(same.isEmpty()).isTrue();
    }

    private static StoredAsset row(int n, String uri, String type, String provider, String day) {
        return new StoredAsset(id(n), uri, type, provider, null, null, OffsetDateTime.parse(day + "T00:00:00Z"));
    }

    private static UUID id(int n) {
        return new UUID(0L, n);
    }
}
