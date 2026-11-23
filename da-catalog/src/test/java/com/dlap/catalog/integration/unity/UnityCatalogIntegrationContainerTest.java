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

package com.dlap.catalog.integration.unity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dlap.common.model.entity.Asset;

import io.unitycatalog.client.ApiClient;
import io.unitycatalog.client.api.TablesApi;
import io.unitycatalog.client.model.TableInfo;

/**
 * Live smoke test for the Unity Catalog sink (ADR-0029): runs a real Unity
 * Catalog OSS server in a container, registers a tabular DLAP asset through
 * {@link UnityCatalogIntegration}, and verifies the {@code catalog.schema.table}
 * entry (with DLAP labels as properties) lands in Unity Catalog. Requires
 * Docker; skipped automatically where Docker is unavailable, like the other
 * Testcontainers tests in the suite.
 */
@Testcontainers
class UnityCatalogIntegrationContainerTest {

    private static final int UC_PORT = 8080;

    @Container
    static final GenericContainer<?> UNITY = new GenericContainer<>(
            DockerImageName.parse("unitycatalog/unitycatalog:main-2f2e32d"))
            .withExposedPorts(UC_PORT)
            .waitingFor(Wait.forHttp("/api/2.1/unity-catalog/catalogs")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private String baseUri() {
        return "http://" + UNITY.getHost() + ":" + UNITY.getMappedPort(UC_PORT) + "/api/2.1/unity-catalog";
    }

    @Test
    void registersTabularAssetAsUnityTableWithLabels() throws Exception {
        UnityCatalogProperties props = new UnityCatalogProperties();
        props.setEnabled(true);
        props.setUri(baseUri());
        props.setCatalog("dlap");
        props.setSchema("default");

        UnityCatalogIntegration integration = new UnityCatalogIntegration(props);

        Asset asset = new Asset("bq://demo/analytics/customer_profiles", "GCP",
                "BIGQUERY_TABLE", "customer_profiles");
        asset.setAssetId(UUID.randomUUID());
        asset.setDescription("Customer profiles table (test)");

        integration.registerAsset(asset, List.of("PII", "CUSTOMER_DATA"));

        // Verify directly via the UC client that the table is now in the metastore.
        ApiClient client = new ApiClient();
        client.updateBaseUri(baseUri());
        TableInfo table = new TablesApi(client).getTable("dlap.default.customer_profiles",
                Boolean.FALSE, Boolean.FALSE);

        assertThat(table.getName()).isEqualTo("customer_profiles");
        assertThat(table.getStorageLocation()).contains("customer_profiles");
        assertThat(table.getProperties())
                .containsEntry("dlap.cloudProvider", "GCP")
                .containsEntry("dlap.labels", "PII,CUSTOMER_DATA");

        // Idempotency: a second registration must not throw.
        integration.registerAsset(asset, List.of("PII", "CUSTOMER_DATA"));
    }
}
