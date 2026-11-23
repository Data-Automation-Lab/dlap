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

package com.dlap.policyengine.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;

/**
 * POL-01: the PDP snapshot carries the lifecycle fields, and a URI-only resource is resolved
 * through the catalog (enforcement-contract.md section 2).
 */
class CatalogAssetSnapshotResolverTest {

    private static final UUID ASSET = UUID.fromString("9f6d0000-0000-4000-8000-000000000001");
    private static final String URI = "bq://proj/dataset/customers";

    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final CatalogAssetSnapshotResolver resolver = new CatalogAssetSnapshotResolver(catalog);

    private static CatalogAssetDTO asset() {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(ASSET);
        a.setAssetUri(URI);
        a.setAssetName("customers");
        a.setCloudProvider("GCP");
        a.setServiceType("BIGQUERY_TABLE");
        a.setAssetType("BIGQUERY_TABLE");
        a.setRegion("US");
        a.setLabels(List.of("PII"));
        a.setLastAccessedAt(Instant.parse("2026-01-01T00:00:00Z"));
        a.setCloudCreatedAt(Instant.parse("2024-05-01T00:00:00Z"));
        a.setLastModifiedAt(Instant.parse("2026-03-01T00:00:00Z"));
        a.setSizeBytes(2048L);
        a.setTechnicalMetadata(Map.of("readCoverage", "partial"));
        a.setLegalHold(Boolean.TRUE);
        a.setRetentionStatus("ON_HOLD");
        return a;
    }

    private static AuthZenEvaluationRequest.Resource resource(String id, Map<String, Object> props) {
        AuthZenEvaluationRequest.Resource r = new AuthZenEvaluationRequest.Resource();
        r.setType("asset");
        r.setId(id);
        r.setProperties(props);
        return r;
    }

    @Test
    void aCatalogIdYieldsTheLifecycleFieldsAndOverridesTheLabelHint() {
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset()));

        IAssetSnapshotResolver.ResolvedResource r = resolver.resolve(
                resource(ASSET.toString(), Map.of("labels", List.of("PUBLIC"))));

        assertThat(r.assetId()).isEqualTo(ASSET.toString());
        assertThat(r.assetUri()).isEqualTo(URI);
        assertThat(r.snapshot())
                .containsEntry("labels", List.of("PII"))
                .containsEntry("lastAccessedAt", "2026-01-01T00:00:00Z")
                .containsEntry("cloudCreatedAt", "2024-05-01T00:00:00Z")
                .containsEntry("lastModifiedAt", "2026-03-01T00:00:00Z")
                .containsEntry("sizeBytes", 2048L)
                .containsEntry("readCoverage", "PARTIAL")
                .containsEntry("legalHold", true)
                .containsEntry("retentionStatus", "ON_HOLD")
                .containsEntry("serviceType", "BIGQUERY_TABLE");
    }

    @Test
    void aUriOnlyResourceIsResolvedThroughTheCatalog() {
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset()));

        IAssetSnapshotResolver.ResolvedResource r = resolver.resolve(resource(null, Map.of("assetUri", URI)));

        assertThat(r.assetId()).isEqualTo(ASSET.toString());
        assertThat(r.assetUri()).isEqualTo(URI);
        assertThat(r.snapshot()).containsEntry("lastAccessedAt", "2026-01-01T00:00:00Z");
    }

    @Test
    void anUnresolvableResourceKeepsOnlyThePepHintsSoEveryOtherFieldIsUnknown() {
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.empty());

        IAssetSnapshotResolver.ResolvedResource r = resolver.resolve(
                resource("not-a-uuid", Map.of("assetUri", URI, "labels", List.of("EU_RESIDENT"))));

        assertThat(r.assetId()).isEqualTo("not-a-uuid");
        assertThat(r.snapshot()).containsOnlyKeys("labels", "assetUri");
        verify(catalog, never()).getAssetById(any());
    }

    @Test
    void aCatalogAssetWithoutLabelsHasAKnownEmptyLabelSet() {
        CatalogAssetDTO a = asset();
        a.setLabels(null);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(a));

        assertThat(resolver.resolve(resource(ASSET.toString(), null)).snapshot()).containsEntry("labels", List.of());
    }

    @Test
    void theCatalogRowTimesNeverStandInForTheProviderCreationTime() {
        CatalogAssetDTO a = asset();
        a.setCloudCreatedAt(null);
        a.setDiscoveredAt(Instant.parse("2020-01-01T00:00:00Z"));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(a));

        assertThat(resolver.resolve(resource(ASSET.toString(), null)).snapshot()).doesNotContainKey("cloudCreatedAt");
    }
}
