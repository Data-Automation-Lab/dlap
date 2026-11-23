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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.BigQueryResource;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;

/**
 * First tests of {@link BigQueryCrawler}: how a dataset and a table are mapped to a
 * {@link BigQueryResource} with a canonical id, a creation time and an observed size (ADR-0046,
 * ADR-0047). The BigQuery client needs credentials and a network, so the SDK objects that carry
 * the data are mocked, the same seam the RDS crawler test uses.
 */
class BigQueryCrawlerTest {

    private static final String PROJECT = "dlap-demo";
    private static final CloudService BIGQUERY = new CloudService("bigquery", "BigQuery", CloudProvider.GCP);
    private static final ResourceType DATASET_TYPE = new ResourceType("gcp_bigquery_dataset", "Dataset", BIGQUERY);
    private static final ResourceType TABLE_TYPE = new ResourceType("gcp_bigquery_table", "Table", BIGQUERY);

    private BigQueryCrawler crawler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType("gcp_bigquery_dataset")).thenReturn(DATASET_TYPE);
        when(registry.getResourceType("gcp_bigquery_table")).thenReturn(TABLE_TYPE);
        crawler = new BigQueryCrawler(registry, mock(KafkaTemplate.class));
        crawler.initSupportedTypes();
    }

    private static Dataset dataset(String project, String name) {
        Dataset dataset = mock(Dataset.class);
        when(dataset.getDatasetId()).thenReturn(DatasetId.of(project, name));
        when(dataset.getLocation()).thenReturn("EU");
        when(dataset.getDescription()).thenReturn("Sales facts");
        when(dataset.getCreationTime()).thenReturn(1_700_000_000_000L);
        when(dataset.getLastModified()).thenReturn(1_700_000_100_000L);
        when(dataset.getLabels()).thenReturn(Map.of("domain", "sales"));
        return dataset;
    }

    private static Table table(String project, String dataset, String name, Long numBytes) {
        Table table = mock(Table.class);
        when(table.getTableId()).thenReturn(TableId.of(project, dataset, name));
        when(table.getDescription()).thenReturn("Orders");
        when(table.getCreationTime()).thenReturn(1_700_000_000_000L);
        when(table.getLastModifiedTime()).thenReturn(1_700_000_200_000L);
        when(table.getNumBytes()).thenReturn(numBytes);
        when(table.getNumRows()).thenReturn(numBytes == null ? null : BigInteger.TEN);
        doReturn(StandardTableDefinition.of(Schema.of())).when(table).getDefinition();
        return table;
    }

    @Test
    void registersTheDatasetAndTableTypes() {
        assertThat(crawler.getSupportedResourceTypes()).containsExactlyInAnyOrder(DATASET_TYPE, TABLE_TYPE);
    }

    @Test
    void aDatasetGetsTheCanonicalUriAndItsCreationTime() {
        BigQueryResource resource = crawler.createResourceFromDataset(dataset(PROJECT, "sales"), PROJECT, DATASET_TYPE);

        assertThat(resource.getUri()).isEqualTo("bq://dlap-demo/sales");
        assertThat(resource.getName()).isEqualTo("sales");
        assertThat(resource.getProjectId()).isEqualTo(PROJECT);
        assertThat(resource.getLocation()).isEqualTo("EU");
        assertThat(resource.getCreatedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        // The dataset-level size is not given by the list API: nothing is forwarded.
        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
    }

    @Test
    void aDatasetNamesItsOwnProjectNotTheConnectionsWhenTheyDiffer() {
        BigQueryResource resource = crawler.createResourceFromDataset(
                dataset("shared-data-1", "public_facts"), PROJECT, DATASET_TYPE);

        assertThat(resource.getUri()).isEqualTo("bq://shared-data-1/public_facts");
        assertThat(resource.getProjectId()).isEqualTo(PROJECT);
    }

    @Test
    void aTableGetsTheCanonicalUriNotTheColonForm() {
        BigQueryResource resource = crawler.createResourceFromTable(
                table(PROJECT, "sales", "orders", 4096L), PROJECT, TABLE_TYPE, "EU");

        assertThat(resource.getUri()).isEqualTo("bq://dlap-demo/sales/orders");
        // The inventory identity stays the project:dataset.table form the crawler always wrote.
        assertThat(resource.getResourceId()).isEqualTo("dlap-demo:sales.orders");
        assertThat(resource.getName()).isEqualTo("sales.orders");
        assertThat(resource.getLocation()).isEqualTo("EU");
        assertThat(resource.getCreatedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void aTableCarriesItsObservedSize() {
        BigQueryResource resource = crawler.createResourceFromTable(
                table(PROJECT, "sales", "orders", 4096L), PROJECT, TABLE_TYPE, "EU");

        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).contains(4096L);
    }

    @Test
    void anEmptyTableHasARealSizeOfZero() {
        BigQueryResource resource = crawler.createResourceFromTable(
                table(PROJECT, "sales", "empty", 0L), PROJECT, TABLE_TYPE, "EU");

        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).contains(0L);
    }

    @Test
    void aViewHasNoStoredBytesSoNoSizeIsForwarded() {
        BigQueryResource resource = crawler.createResourceFromTable(
                table(PROJECT, "sales", "orders_v", null), PROJECT, TABLE_TYPE, "EU");

        assertThat(resource.getUri()).isEqualTo("bq://dlap-demo/sales/orders_v");
        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
    }

    @Test
    void aTableOfAnotherProjectKeepsItsOwnProjectInTheId() {
        BigQueryResource resource = crawler.createResourceFromTable(
                table("shared-data-1", "public_facts", "rates", 1L), PROJECT, TABLE_TYPE, "US");

        assertThat(resource.getUri()).isEqualTo("bq://shared-data-1/public_facts/rates");
    }

    @Test
    void datasetAndTableNamesKeepTheirCase() {
        BigQueryResource resource = crawler.createResourceFromTable(
                table(PROJECT, "Sales_EU", "Orders2026", 1L), PROJECT, TABLE_TYPE, "EU");

        // BigQuery dataset and table names are case sensitive by default (ADR-0047 item 3).
        assertThat(resource.getUri()).isEqualTo("bq://dlap-demo/Sales_EU/Orders2026");
    }

    @Test
    void theBridgeResolvesTheSameIdsTheCrawlerStored() {
        BigQueryResource dataset = crawler.createResourceFromDataset(dataset(PROJECT, "sales"), PROJECT, DATASET_TYPE);
        BigQueryResource table = crawler.createResourceFromTable(
                table(PROJECT, "sales", "orders", 1L), PROJECT, TABLE_TYPE, "EU");

        assertThat(CrawlerAssetIds.resolve(dataset)).hasValueSatisfying(id -> assertThat(id.uri()).isEqualTo(dataset.getUri()));
        assertThat(CrawlerAssetIds.resolve(table)).hasValueSatisfying(id -> assertThat(id.uri()).isEqualTo(table.getUri()));
    }
}
