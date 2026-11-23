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

package com.dlap.policyengine.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.dto.EventStreamDTO;

/**
 * SEC-04: the asset identity and labels of an Event Center event, read from every payload
 * shape the stream consumer records. Asset identifiers come out canonical (ADR-0047).
 */
class EventFactsTest {

    private static EventStreamDTO event(Map<String, Object> data) {
        EventStreamDTO e = new EventStreamDTO();
        e.setEventData(data);
        return e;
    }

    @Test
    void topLevelAssetUriIsNormalized() {
        assertThat(EventFacts.canonicalAssetUris(event(Map.of("assetUri", "s3://finance/reports/q1.csv"))))
                .containsExactly("s3://finance/reports/q1.csv");
        // A BigQuery audit-log alias converges to the canonical bq:// form.
        assertThat(EventFacts.canonicalAssetUris(event(Map.of(
                "asset_uri", "projects/analytics-prod/datasets/sales/tables/orders"))))
                .containsExactly("bq://analytics-prod/sales/orders");
    }

    @Test
    void catalogChangeEventSnapshotIsReadWithItsHints() {
        // da-catalog AssetChangeEvent: the native id sits in assetSnapshot.
        Map<String, Object> data = Map.of(
                "eventType", "ASSET_UPDATED",
                "assetSnapshot", Map.of(
                        "assetType", "GCS_BUCKET",
                        "cloudProvider", "GCP",
                        "nativeAssetId", "gs://landing-zone"));
        assertThat(EventFacts.canonicalAssetUris(event(data))).containsExactly("gs://landing-zone");
    }

    @Test
    void targetResourceAndMatchSnapshotShapesAreRead() {
        assertThat(EventFacts.canonicalAssetUris(event(Map.of(
                "targetResource", Map.of("canonicalUri", "gs://raw/2024/01/a.json")))))
                .containsExactly("gs://raw/2024/01/a.json");
        assertThat(EventFacts.canonicalAssetUris(event(Map.of(
                "matchSnapshot", Map.of("assetUri", "s3://ml-data")))))
                .containsExactly("s3://ml-data");
    }

    @Test
    void unresolvableIdentifiersAreLeftOut() {
        assertThat(EventFacts.canonicalAssetUris(event(Map.of("assetUri", "not an asset id")))).isEmpty();
        assertThat(EventFacts.canonicalAssetUris(event(Map.of("assetUri", 42)))).isEmpty();
        assertThat(EventFacts.canonicalAssetUris(event(Map.of()))).isEmpty();
        assertThat(EventFacts.canonicalAssetUris(new EventStreamDTO())).isEmpty();
        assertThat(EventFacts.canonicalAssetUris(null)).isEmpty();
    }

    @Test
    void labelsAreReadFromEveryShapeAndLowerCased() {
        assertThat(EventFacts.labels(event(Map.of("labels", List.of("PII", "Finance")))))
                .containsExactlyInAnyOrder("pii", "finance");
        assertThat(EventFacts.labels(event(Map.of("label", "Production")))).containsExactly("production");
        // Policy action addLabel parameters.
        assertThat(EventFacts.labels(event(Map.of("actionParameters", Map.of("labelName", "GDPR")))))
                .containsExactly("gdpr");
        // da-catalog label assignment event: comma-separated details.
        assertThat(EventFacts.labels(event(Map.of("labelDetails", Map.of("details", Map.of(
                "labels_assigned", "PII, Restricted", "updated_section", "labels"))))))
                .containsExactlyInAnyOrder("pii", "restricted");
        assertThat(EventFacts.labels(event(Map.of("labelDetails", Map.of("details", Map.of(
                "label_removed", "LEGAL_HOLD"))))))
                .containsExactly("legal_hold");
    }

    @Test
    void eventWithoutLabelsHasNone() {
        assertThat(EventFacts.labels(event(Map.of("assetUri", "s3://b")))).isEmpty();
        assertThat(EventFacts.labels(null)).isEmpty();
    }
}
