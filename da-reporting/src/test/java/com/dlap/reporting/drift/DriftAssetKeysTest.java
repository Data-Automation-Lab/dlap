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

package com.dlap.reporting.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dlap.common.assetid.AssetProvider;

/** Canonical asset keys on both sides of the drift join. */
class DriftAssetKeysTest {

    private static DriftAssetKeys.AssetKey canonical(String uri) {
        return new DriftAssetKeys.AssetKey(uri, true);
    }

    @Test
    void bigQueryResourceName_inTheReadJoinsTheDecisionsBqUri() {
        DriftAssetKeys.AssetKey read = DriftAssetKeys.forRead("", "projects/acme-prod/datasets/d1/tables/t1", AssetProvider.GCP)
                .orElseThrow();
        DriftAssetKeys.AssetKey decision = DriftAssetKeys.forDecision("bq://acme-prod/d1/t1", "9f6d2a1e-0000-4000-8000-000000000001")
                .orElseThrow();

        assertThat(read).isEqualTo(canonical("bq://acme-prod/d1/t1"));
        assertThat(decision).isEqualTo(read);
    }

    @Test
    void objectRead_rollsUpToItsBucket() {
        assertThat(DriftAssetKeys.forRead("gs://bucket-a/path/file.csv", "ignored", AssetProvider.GCP))
                .contains(canonical("gs://bucket-a"));
        assertThat(DriftAssetKeys.forRead("", "projects/_/buckets/bucket-a/objects/path/file.csv", AssetProvider.GCP))
                .contains(canonical("gs://bucket-a"));
        assertThat(DriftAssetKeys.forDecision("gs://bucket-a/path/file.csv", null))
                .contains(canonical("gs://bucket-a"));
    }

    @Test
    void canonicalUri_winsOverTheNativeId() {
        assertThat(DriftAssetKeys.forRead("s3://bucket-b", "bucket-b", AssetProvider.AWS))
                .contains(canonical("s3://bucket-b"));
    }

    @Test
    void nativeId_needsTheProviderHint() {
        assertThat(DriftAssetKeys.forRead("", "arn:aws:s3:::bucket-c", AssetProvider.AWS))
                .contains(canonical("s3://bucket-c"));
    }

    @Test
    void idThatDoesNotNormalize_isKeptAsWritten() {
        String uuid = "9f6d2a1e-0000-4000-8000-000000000001";
        assertThat(DriftAssetKeys.forRead(" ", " " + uuid + " ", null))
                .contains(new DriftAssetKeys.AssetKey(uuid, false));
        assertThat(DriftAssetKeys.forDecision(null, uuid)).contains(new DriftAssetKeys.AssetKey(uuid, false));
        assertThat(DriftAssetKeys.forDecision("not a uri", uuid))
                .as("an assetUri that does not normalize still names the resource")
                .contains(new DriftAssetKeys.AssetKey("not a uri", false));
    }

    @Test
    void decisionWithOnlyACanonicalAssetId_normalizesIt() {
        assertThat(DriftAssetKeys.forDecision("", "s3://bucket-d")).contains(canonical("s3://bucket-d"));
    }

    @Test
    void noTarget_isEmpty() {
        assertThat(DriftAssetKeys.forRead("", "", AssetProvider.GCP)).isEmpty();
        assertThat(DriftAssetKeys.forRead(null, null, null)).isEmpty();
        assertThat(DriftAssetKeys.forDecision(" ", null)).isEmpty();
    }
}
