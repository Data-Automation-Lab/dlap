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

package com.dlap.discovery.crawler.service.unity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.config.UnityCatalogDiscoveryProperties;
import com.dlap.discovery.crawler.model.unity.UnityCatalogResource;
import com.dlap.discovery.event.service.type.ResourceEvent;

import io.unitycatalog.client.model.DataSourceFormat;
import io.unitycatalog.client.model.TableInfo;
import io.unitycatalog.client.model.TableType;

/**
 * Unit tests for {@link UnityCatalogResourceCrawler}. Uses hand-rolled fakes
 * (a capturing {@link KafkaTemplate} subclass) rather than Mockito, verifying
 * the ResourceEvent shape a discovered Unity Catalog table would publish:
 * provider = UNITY, changeType = CREATE, payload URI = catalog.schema.table.
 */
class UnityCatalogResourceCrawlerTest {

    /** Capturing KafkaTemplate: records the last send(...) call, never contacts a broker. */
    private static final class CapturingKafkaTemplate extends KafkaTemplate<String, ResourceEvent> {
        private String topic;
        private String key;
        private ResourceEvent event;

        CapturingKafkaTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of()));
        }

        @Override
        public CompletableFuture<SendResult<String, ResourceEvent>> send(String topic, String key,
                ResourceEvent data) {
            this.topic = topic;
            this.key = key;
            this.event = data;
            return CompletableFuture.completedFuture(null);
        }
    }

    private ResourceType unityTableType;
    private CapturingKafkaTemplate kafkaTemplate;
    private UnityCatalogResourceCrawler crawler;

    @BeforeEach
    void setUp() {
        CloudService unityService = new CloudService("unity_catalog", "Unity Catalog", CloudProvider.UNITY);
        unityTableType = new ResourceType(UnityCatalogResource.TABLE_TYPE_ID, "Unity Catalog Table", unityService);

        UnityCatalogDiscoveryProperties props = new UnityCatalogDiscoveryProperties();
        props.setUri("http://localhost:8080/api/2.1/unity-catalog");

        kafkaTemplate = new CapturingKafkaTemplate();
        crawler = new UnityCatalogResourceCrawler(props, kafkaTemplate);
    }

    @Test
    void getProviderIsUnity() {
        assertEquals(CloudProvider.UNITY, crawler.getProvider());
    }

    @Test
    void resourceIdIsThreeLevelNameAndTheUriWaitsForTheMetastore() {
        UnityCatalogResource resource =
                new UnityCatalogResource(unityTableType, "main", "sales", "orders");

        // A three-part name is unique only inside a metastore, so the canonical uri is stored by the
        // crawler, which knows it; the model does not write a name that is not an identifier.
        assertNull(resource.getUri());
        assertEquals("main.sales.orders", resource.getResourceId());
        assertEquals("orders", resource.getName());
        assertEquals(CloudProvider.UNITY, resource.getCloudProviderEnum());
        assertEquals("main", resource.getCatalogName());
        assertEquals("sales", resource.getSchemaName());
        assertEquals("orders", resource.getTableName());
    }

    @Test
    void nonUnityResourceTypeIsRejected() {
        CloudService gcpService = new CloudService("bigquery", "BigQuery", CloudProvider.GCP);
        ResourceType gcpType = new ResourceType("gcp_bigquery_table", "BigQuery Table", gcpService);

        assertThrows(IllegalArgumentException.class,
                () -> new UnityCatalogResource(gcpType, "c", "s", "t"));
    }

    @Test
    void createResourceFromTableMapsFields() {
        TableInfo table = new TableInfo()
                .name("orders")
                .catalogName("main")
                .schemaName("sales")
                .comment("Order facts")
                .tableType(TableType.EXTERNAL)
                .dataSourceFormat(DataSourceFormat.DELTA)
                .storageLocation("s3://bucket/orders")
                .tableId("tbl-123")
                .createdAt(1_600_000_000_000L)
                .updatedAt(1_600_000_100_000L)
                .properties(Map.of("pii", "true"));

        UnityCatalogResource resource =
                crawler.createResourceFromTable(table, "main", "sales", unityTableType, "metastore-1");

        assertNotNull(resource);
        // Canonical id (ADR-0047): the connection's account id is the metastore.
        assertEquals("uc://metastore-1/main/sales/orders", resource.getUri());
        assertEquals("main.sales.orders", resource.getResourceId());
        assertEquals("metastore-1", resource.getAccountId());
        assertEquals("Order facts", resource.getDescription());
        assertEquals("true", resource.getTags().get("pii"));

        Map<String, String> meta = resource.getTechnicalMetadata();
        assertEquals("main", meta.get("catalogName"));
        assertEquals("sales", meta.get("schemaName"));
        assertEquals("orders", meta.get("tableName"));
        assertEquals("tbl-123", meta.get("tableId"));
        assertEquals("EXTERNAL", meta.get("tableType"));
        assertEquals("DELTA", meta.get("dataSourceFormat"));
        assertEquals("s3://bucket/orders", meta.get("storageLocation"));
    }

    @Test
    void createResourceFromTableWithoutMetastoreLeavesTheUriUnset() {
        TableInfo table = new TableInfo().name("orders");

        UnityCatalogResource resource = crawler.createResourceFromTable(table, "main", "sales", unityTableType, null);

        // A three-part name is unique only inside a metastore; with none, nothing is guessed. The
        // table is still published, keyed by its three-level resource id.
        assertNotNull(resource);
        assertNull(resource.getUri());
        assertEquals("main.sales.orders", resource.getResourceId());
    }

    @Test
    void createResourceFromTableLowerCasesNamesAsUnityCatalogStoresThem() {
        TableInfo table = new TableInfo().name("Orders");

        UnityCatalogResource resource = crawler.createResourceFromTable(table, "Main", "Sales", unityTableType, "Meta-1");

        assertNotNull(resource);
        assertEquals("uc://meta-1/main/sales/orders", resource.getUri());
    }

    @Test
    void createResourceFromTableSkipsNullName() {
        TableInfo table = new TableInfo().catalogName("main").schemaName("sales");
        assertTrue(crawler.createResourceFromTable(table, "main", "sales", unityTableType, "acct") == null);
    }

    @Test
    void publishResourceEventEmitsUnityCreateEvent() {
        UnityCatalogResource resource =
                new UnityCatalogResource(unityTableType, "main", "sales", "orders");
        resource.setAccountId("metastore-1");

        crawler.publishResourceEvent("metastore-1", resource, ChangeType.CREATE);

        ResourceEvent event = kafkaTemplate.event;
        assertNotNull(event, "an event must have been published");
        assertEquals("main.sales.orders", kafkaTemplate.key, "event is keyed on the three-level name");
        assertEquals(CloudProvider.UNITY.toString(), event.getCloudProvider());
        assertEquals("metastore-1", event.getAccountId());
        assertEquals(ChangeType.CREATE, event.getChangeType());
        assertNotNull(event.getEventId());
        assertNotNull(event.getEventTimestamp());
        assertEquals("Crawler", event.getSource());
        assertSame(resource, event.getPayload());
        assertEquals("main.sales.orders", event.getPayload().getResourceId());
    }

    @Test
    void supportedTypesEmptyUntilInitialised() {
        // Without the hierarchy registry wired (a pure unit context), the crawler
        // starts with no supported types; registration is driven by initSupportedTypes().
        List<ResourceType> types = new ArrayList<>(crawler.getSupportedResourceTypes());
        assertTrue(types.isEmpty());
    }
}
