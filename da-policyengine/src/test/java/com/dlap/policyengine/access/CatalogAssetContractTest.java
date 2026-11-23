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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.dsl.AssetSnapshots;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Q-479: pins the policy engine's view of the single-asset read to the catalog's committed API
 * contract ({@code docs/api/openapi/catalog.json}, written by the catalog's OpenAPI export test). The
 * event path ({@code LifecyclePolicyConsumer}) and the PDP path ({@link CatalogAssetSnapshotResolver})
 * read {@code readCoverage} from {@code GET /assets/{id}}; if the catalog stops sending it, or this DTO
 * stops reading it, a test fails here and not as a silent "unknown coverage" in production.
 */
class CatalogAssetContractTest {

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static JsonNode assetOutput;

    @BeforeAll
    static void readContract() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("docs/api/openapi/catalog.json"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("docs/api/openapi/catalog.json was not found above "
                    + System.getProperty("user.dir"));
        }
        JsonNode catalog = JSON.readTree(Files.readString(dir.resolve("docs/api/openapi/catalog.json")));
        assetOutput = catalog.at("/components/schemas/AssetOutputDTO/properties");
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void theCatalogContractCarriesTheReadCoverageFields() {
        assertThat(names(assetOutput)).contains("readCoverage", "readCoverageReasons");
    }

    @Test
    void everyFieldTheClientReadsIsInTheCatalogContract() throws Exception {
        Set<String> read = new TreeSet<>();
        for (PropertyDescriptor p : Introspector.getBeanInfo(CatalogAssetDTO.class, Object.class).getPropertyDescriptors()) {
            if (p.getWriteMethod() != null) {
                read.add(p.getName());
            }
        }

        assertThat(names(assetOutput)).containsAll(read);
    }

    @Test
    void theCatalogsFullCoverageReachesTheSnapshotOfTheEventAndPdpPaths() throws Exception {
        UUID id = UUID.fromString("9f6d0000-0000-4000-8000-000000000002");
        // exactly the field names of the contract; the values are what the catalog sends for a FULL asset
        String body = """
                {"assetId":"%s","assetUri":"gs://dlap-demo-curated/exports/2026.csv","cloudProvider":"GCP",
                 "serviceType":"GCS_OBJECT","lastAccessedAt":"2026-01-01T00:00:00Z",
                 "readCoverage":"FULL","readCoverageReasons":["CloudTrail data events cover the bucket"]}
                """.formatted(id);
        assertThat(names(JSON.readTree(body))).isSubsetOf(names(assetOutput));
        CatalogAssetDTO dto = JSON.readValue(body, CatalogAssetDTO.class);
        ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
        when(catalog.getAssetById(id)).thenReturn(Optional.of(dto));

        AuthZenEvaluationRequest.Resource resource = new AuthZenEvaluationRequest.Resource();
        resource.setType("asset");
        resource.setId(id.toString());
        var resolved = new CatalogAssetSnapshotResolver(catalog).resolve(resource);

        assertThat(dto.getReadCoverage()).isEqualTo("FULL");
        assertThat(resolved.snapshot()).containsEntry(AssetSnapshots.READ_COVERAGE, AssetSnapshots.COVERAGE_FULL);
        assertThat(AssetSnapshots.fromCatalogAsset(dto)).containsEntry(AssetSnapshots.READ_COVERAGE, "FULL");
    }

    @Test
    void theCoverageTimeAndStartOfTheContractReachTheSnapshotOfTheEventAndPdpPaths() throws Exception {
        // POL-03 (Q-837, Q-434): the two COV-02 fields of the single-asset read, by their contract names.
        assertThat(names(assetOutput)).contains("readCoverageObservedAt", "readCoverageSince");
        String body = """
                {"assetId":"9f6d0000-0000-4000-8000-000000000003","readCoverage":"FULL",
                 "readCoverageObservedAt":"2026-10-19T06:41:00Z","readCoverageSince":"2026-09-20T06:41:00Z"}
                """;
        assertThat(names(JSON.readTree(body))).isSubsetOf(names(assetOutput));

        CatalogAssetDTO dto = JSON.readValue(body, CatalogAssetDTO.class);

        assertThat(AssetSnapshots.fromCatalogAsset(dto))
                .containsEntry(AssetSnapshots.READ_COVERAGE_OBSERVED_AT, "2026-10-19T06:41:00Z")
                .containsEntry(AssetSnapshots.READ_COVERAGE_SINCE, "2026-09-20T06:41:00Z");
        // Read as text, so a value of another shape never fails the asset read.
        CatalogAssetDTO odd = JSON.readValue("{\"readCoverage\":\"FULL\",\"readCoverageObservedAt\":1729320060}",
                CatalogAssetDTO.class);
        assertThat(AssetSnapshots.fromCatalogAsset(odd)).containsEntry(AssetSnapshots.READ_COVERAGE_OBSERVED_AT,
                "1729320060");
    }

    @Test
    void anAssetWithoutCoverageStaysUnknownInTheSnapshot() throws Exception {
        CatalogAssetDTO dto = JSON.readValue("{\"assetUri\":\"gs://b/k\"}", CatalogAssetDTO.class);

        assertThat(AssetSnapshots.fromCatalogAsset(dto)).doesNotContainKey(AssetSnapshots.READ_COVERAGE);
        assertThat(Arrays.asList(AssetSnapshots.LIFECYCLE_KEYS.toArray())).contains(AssetSnapshots.READ_COVERAGE);
    }
}
