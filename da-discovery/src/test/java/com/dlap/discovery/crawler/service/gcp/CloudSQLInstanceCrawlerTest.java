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

package com.dlap.discovery.crawler.service.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.CloudSQLResource;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.Settings;

/**
 * First tests of {@link CloudSQLInstanceCrawler}: how a Cloud SQL Admin API instance is mapped to
 * a {@link CloudSQLResource} with a canonical id and a creation time (ADR-0047). The Admin API
 * model classes are plain data, so real instances are built.
 */
class CloudSQLInstanceCrawlerTest {

    private static final String PROJECT = "dlap-demo";
    private static final ResourceType SQL_TYPE = new ResourceType("gcp_cloudsql_instance", "Cloud SQL Instance",
            new CloudService("cloudsql", "Cloud SQL", CloudProvider.GCP));

    private CloudSQLInstanceCrawler crawler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType("gcp_cloudsql_instance")).thenReturn(SQL_TYPE);
        crawler = new CloudSQLInstanceCrawler(registry, mock(KafkaTemplate.class));
    }

    private static DatabaseInstance instance(String name) {
        return new DatabaseInstance()
                .setName(name)
                .setRegion("us-central1")
                .setGceZone("us-central1-a")
                .setDatabaseVersion("POSTGRES_15")
                .setState("RUNNABLE")
                .setConnectionName(PROJECT + ":us-central1:" + name)
                .setCreateTime("2026-01-02T03:04:05.678Z")
                .setSettings(new Settings().setTier("db-custom-2-7680"));
    }

    @Test
    void registersTheCloudSqlType() {
        assertThat(crawler.getSupportedResourceTypes()).containsExactly(SQL_TYPE);
    }

    @Test
    void anInstanceGetsTheCanonicalCloudAssetName() {
        CloudSQLResource resource = crawler.createResourceFromInstance(instance("orders-db"), PROJECT, SQL_TYPE);

        assertThat(resource.getUri()).isEqualTo("//cloudsql.googleapis.com/projects/dlap-demo/instances/orders-db");
        assertThat(resource.getResourceId()).isEqualTo("orders-db");
        assertThat(resource.getProjectId()).isEqualTo(PROJECT);
        // The region is an attribute, not identity.
        assertThat(resource.getRegion()).isEqualTo("us-central1");
    }

    @Test
    void anInstanceCarriesItsCreationTime() {
        CloudSQLResource resource = crawler.createResourceFromInstance(instance("orders-db"), PROJECT, SQL_TYPE);

        assertThat(resource.getCreatedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05.678Z"));
    }

    @Test
    void anInstanceWithAnUnparsableCreationTimeKeepsNoCreationTime() {
        DatabaseInstance broken = instance("orders-db").setCreateTime("yesterday");

        CloudSQLResource resource = crawler.createResourceFromInstance(broken, PROJECT, SQL_TYPE);

        assertThat(resource.getCreatedAt()).isNull();
        assertThat(resource.getUri()).isEqualTo("//cloudsql.googleapis.com/projects/dlap-demo/instances/orders-db");
    }

    @Test
    void anInstanceHasNoObservedSizeBecauseTheApiReportsOnlyProvisionedStorage() {
        CloudSQLResource resource = crawler.createResourceFromInstance(instance("orders-db"), PROJECT, SQL_TYPE);

        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
    }

    @Test
    void anInstanceNameThatIsNotAValidCloudSqlNameGetsNoUri() {
        CloudSQLResource resource = crawler.createResourceFromInstance(instance("9-bad-name"), PROJECT, SQL_TYPE);

        assertThat(resource.getUri()).isNull();
    }

    @Test
    void theBridgeResolvesTheSameIdTheCrawlerStored() {
        CloudSQLResource resource = crawler.createResourceFromInstance(instance("orders-db"), PROJECT, SQL_TYPE);

        assertThat(CrawlerAssetIds.resolve(resource))
                .hasValueSatisfying(id -> assertThat(id.uri()).isEqualTo(resource.getUri()));
    }
}
