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


package com.dlap.autodelete.retire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.client.CatalogAssetFeignClient;
import com.dlap.autodelete.dto.CatalogAssetDTO;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Q-619: the facts the executor reads from the catalog include the asset's size, which the storage-reclaimed
 * KPI later sums once the provider's log proves the deletion.
 */
class CatalogFactsClientTest {

    @Test
    void theCatalogSizeComesWithTheOtherFacts() {
        CatalogAssetFeignClient feign = mock(CatalogAssetFeignClient.class);
        UUID id = UUID.randomUUID();
        CatalogAssetDTO dto = new CatalogAssetDTO();
        dto.setAssetId(id);
        dto.setAssetUri("gs://bucket");
        dto.setCloudProvider("GCP");
        dto.setServiceType("GCS_BUCKET");
        dto.setLegalHold(false);
        dto.setReadCoverage("FULL");
        dto.setReadCoverageReasons(List.of("dataAccess logs on"));
        dto.setSizeBytes(123_456L);
        when(feign.getAssetById(id)).thenReturn(dto);

        Optional<AssetFacts> facts = new CatalogFactsClient(feign).read(id);

        assertThat(facts).isPresent();
        assertThat(facts.get().sizeBytes()).isEqualTo(123_456L);
        assertThat(facts.get().fullReadCoverage()).isTrue();
        assertThat(facts.get().heldOrUnknown()).isFalse();
    }

    @Test
    void anUnknownSizeStaysUnknown() {
        CatalogAssetFeignClient feign = mock(CatalogAssetFeignClient.class);
        UUID id = UUID.randomUUID();
        when(feign.getAssetById(id)).thenReturn(new CatalogAssetDTO());

        assertThat(new CatalogFactsClient(feign).read(id).orElseThrow().sizeBytes()).isNull();
    }
}
