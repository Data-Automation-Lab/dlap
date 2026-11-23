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

package com.dlap.autodelete.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.autodelete.client.ResilientCatalogAssetClient;
import com.dlap.autodelete.dto.CatalogAssetDTO;
import com.dlap.autodelete.service.AssetLocator;
import com.dlap.autodelete.service.AssetLocatorResolver;
import com.dlap.autodelete.service.storage.impl.AwsCloudDeletionService;
import com.dlap.autodelete.service.storage.impl.AzureCloudDeletionService;
import com.dlap.autodelete.service.storage.impl.GcpCloudDeletionService;

/**
 * The provider dispatch stays fail closed now that the locator is built by the asset-id
 * normalizer: a provider with no deletion service is refused, never routed to a default.
 */
class CloudDeletionServiceFactoryTest {

    private final CloudDeletionServiceFactory factory = new CloudDeletionServiceFactory(List.of(
            new GcpCloudDeletionService(true),
            new AwsCloudDeletionService(true),
            new AzureCloudDeletionService(true)));

    @ParameterizedTest
    @ValueSource(strings = {"GCP", "gcp", " Gcp "})
    void gcpDispatchesToTheGcpService(String provider) {
        assertThat(factory.forProvider(provider)).isInstanceOf(GcpCloudDeletionService.class);
    }

    @Test
    void awsAndAzureDispatchToTheirOwnServices() {
        assertThat(factory.forProvider("AWS")).isInstanceOf(AwsCloudDeletionService.class);
        assertThat(factory.forProvider("AZURE")).isInstanceOf(AzureCloudDeletionService.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNITY", "DATABRICKS", "SNOWFLAKE", "OCI", "ONPREM", "UNKNOWN", "UNSPECIFIED"})
    void aProviderWithNoDeletionServiceIsRefusedNotDefaulted(String provider) {
        assertThatThrownBy(() -> factory.forProvider(provider))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void aBlankProviderIsRefused(String provider) {
        assertThatThrownBy(() -> factory.forProvider(provider)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyProviderTheResolverCanReturnIsEitherServedOrRefusedByTheFactory() {
        // The resolver hands an unknown catalog provider through unchanged; the factory is what
        // refuses it. Together they must never route an asset of one cloud to another.
        ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
        CatalogAssetDTO dto = new CatalogAssetDTO();
        dto.setCloudProvider("ONPREM");
        dto.setAssetUri("nfs://share/file");
        UUID assetId = UUID.randomUUID();
        when(catalog.getAssetById(assetId)).thenReturn(Optional.of(dto));

        AssetLocator locator = new AssetLocatorResolver(catalog, "GCP").resolve(assetId.toString());

        assertThat(locator.provider()).isEqualTo("ONPREM");
        assertThatThrownBy(() -> factory.forProvider(locator.provider()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
