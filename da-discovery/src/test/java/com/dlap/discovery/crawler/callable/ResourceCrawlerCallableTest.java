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

package com.dlap.discovery.crawler.callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.callable.ResourceCrawlerCallable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for ResourceCrawlerCallable using real CloudHierarchyRegistry.
 * This test uses actual cloud configuration instead of mocks to test real functionality.
 *
 * <p>Plain Mockito unit test: every collaborator is a hand-built object or a
 * Mockito mock, so no Spring context is required. The previous
 * {@code @SpringBootTest} bootstrapped the entire application (GCP clients,
 * admin-service client, log analyzers) and failed in any environment without
 * live cloud credentials.
 */
@ExtendWith(MockitoExtension.class)
public class ResourceCrawlerCallableTest {

    @Mock
    private IResourceCrawlerRegistry crawlerRegistry;

    @Mock
    private IResourceCrawler<CloudResource> crawler1;

    @Mock
    private IResourceCrawler<CloudResource> crawler2;

    // Use real CloudHierarchyRegistry instead of mock
    private CloudHierarchyRegistry cloudHierarchyRegistry;

    @Mock
    private ICatalogService catalogService;

    @Mock
    private List<CloudProviderHandler> cloudProviderHandlers;

    private DiscoveryJob job;
    private ResourceCrawlerCallable callable;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        job = new DiscoveryJob(UUID.randomUUID());
        job.setJobType(JobType.RESOURCE_CRAWLER);
        job.setCloudProvider(CloudProvider.GCP);
        job.setAccountId("test-account");

        // Initialize real CloudHierarchyRegistry with test configuration
        cloudHierarchyRegistry = new CloudHierarchyRegistry(createTestCloudHierarchyProperties());
        cloudHierarchyRegistry.initialize();

        callable = new ResourceCrawlerCallable(job, crawlerRegistry, catalogService, cloudHierarchyRegistry, cloudProviderHandlers);
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    void call_NoCrawlersFound_ReturnsFalse() throws Exception {
        when(crawlerRegistry.getCrawlersForProvider(CloudProvider.GCP)).thenReturn(List.of());

        Boolean result = callable.call();

        assertFalse(result);
        verify(crawlerRegistry).getCrawlersForProvider(CloudProvider.GCP);
        verifyNoInteractions(crawler1, crawler2, catalogService);
    }

    @Test
    void call_CrawlersFound_TriggersCrawlersAndReturnsTrue() throws Exception {
        when(crawlerRegistry.getCrawlersForProvider(CloudProvider.GCP)).thenReturn(List.of(crawler1, crawler2));
        doNothing().when(crawler1).discoverResourcesAsync(eq("test-account"), anyMap());
        doNothing().when(crawler2).discoverResourcesAsync(eq("test-account"), anyMap());

        Boolean result = callable.call();

        assertTrue(result);
        verify(crawlerRegistry).getCrawlersForProvider(CloudProvider.GCP);
        verify(crawler1).discoverResourcesAsync(eq("test-account"), anyMap());
        verify(crawler2).discoverResourcesAsync(eq("test-account"), anyMap());
    }

    @Test
    void call_WithSpecificResourceTypes_UsesGetCrawlersForTypes() throws Exception {
        // Use real resource type IDs from the cloud hierarchy configuration
        List<String> typeNames = List.of("gcp_bigquery_dataset", "gcp_bigquery_table");
        job.setParameters(Map.of("resourceTypesToCrawl", typeNames));
        callable = new ResourceCrawlerCallable(job, crawlerRegistry, catalogService, cloudHierarchyRegistry, cloudProviderHandlers);

        // Mock the crawler registry to return a crawler for the specific resource types
        when(crawlerRegistry.getCrawlersForTypes(eq(CloudProvider.GCP), anyList())).thenReturn(List.of(crawler1));
        doNothing().when(crawler1).discoverResourcesAsync(eq("test-account"), anyMap());

        Boolean result = callable.call();

        assertTrue(result);
        verify(crawlerRegistry).getCrawlersForTypes(eq(CloudProvider.GCP), anyList());
        verify(crawlerRegistry, never()).getCrawlersForProvider(any());
        verify(crawler1).discoverResourcesAsync(eq("test-account"), anyMap());
    }

    @Test
    void call_CrawlerTriggerFails_ContinuesAndReturnsTrueIfAnySucceed() throws Exception {
        when(crawlerRegistry.getCrawlersForProvider(CloudProvider.GCP)).thenReturn(List.of(crawler1, crawler2));
        doThrow(new RuntimeException("Trigger failed!")).when(crawler1).discoverResourcesAsync(anyString(), anyMap());
        doNothing().when(crawler2).discoverResourcesAsync(eq("test-account"), anyMap());

        Boolean result = callable.call();

        assertTrue(result);
        verify(crawlerRegistry).getCrawlersForProvider(CloudProvider.GCP);
        verify(crawler1).discoverResourcesAsync(eq("test-account"), anyMap());
        verify(crawler2).discoverResourcesAsync(eq("test-account"), anyMap());
    }

    @Test
    void call_AllCrawlersFail_ReturnsFalse() throws Exception {
        when(crawlerRegistry.getCrawlersForProvider(CloudProvider.GCP)).thenReturn(List.of(crawler1, crawler2));
        doThrow(new RuntimeException("Trigger failed!")).when(crawler1).discoverResourcesAsync(anyString(), anyMap());
        doThrow(new RuntimeException("Trigger failed!")).when(crawler2).discoverResourcesAsync(anyString(), anyMap());

        Boolean result = callable.call();

        assertFalse(result);
        verify(crawlerRegistry).getCrawlersForProvider(CloudProvider.GCP);
        verify(crawler1).discoverResourcesAsync(eq("test-account"), anyMap());
        verify(crawler2).discoverResourcesAsync(eq("test-account"), anyMap());
    }

    @Test
    void call_WithRealResourceTypes_ValidatesCloudHierarchyRegistry() throws Exception {
        // Test that the real CloudHierarchyRegistry can resolve actual resource types
        List<String> typeNames = List.of("gcp_bigquery_dataset", "gcp_gcs_bucket");
        job.setParameters(Map.of("resourceTypesToCrawl", typeNames));
        callable = new ResourceCrawlerCallable(job, crawlerRegistry, catalogService, cloudHierarchyRegistry, cloudProviderHandlers);

        // Verify that the CloudHierarchyRegistry can resolve these resource types
        assertNotNull(cloudHierarchyRegistry.getResourceType("gcp_bigquery_dataset"));
        assertNotNull(cloudHierarchyRegistry.getResourceType("gcp_gcs_bucket"));

        // Verify the resource types belong to GCP
        assertEquals(CloudProvider.GCP, cloudHierarchyRegistry.getResourceType("gcp_bigquery_dataset").service().provider());
        assertEquals(CloudProvider.GCP, cloudHierarchyRegistry.getResourceType("gcp_gcs_bucket").service().provider());

        when(crawlerRegistry.getCrawlersForTypes(eq(CloudProvider.GCP), anyList())).thenReturn(List.of(crawler1));
        doNothing().when(crawler1).discoverResourcesAsync(eq("test-account"), anyMap());

        Boolean result = callable.call();

        assertTrue(result);
        verify(crawlerRegistry).getCrawlersForTypes(eq(CloudProvider.GCP), anyList());
    }

    /**
     * Creates test cloud hierarchy properties that match the real configuration.
     * This ensures we're testing with actual cloud provider configurations.
     */
    private com.dlap.discovery.common.config.CloudHierarchyProperties createTestCloudHierarchyProperties() {
        com.dlap.discovery.common.config.CloudHierarchyProperties properties =
            new com.dlap.discovery.common.config.CloudHierarchyProperties();

        // Add GCP provider configuration (matching the real application.yml)
        com.dlap.discovery.common.config.CloudHierarchyProperties.ProviderConfig gcpProvider =
            new com.dlap.discovery.common.config.CloudHierarchyProperties.ProviderConfig();
        gcpProvider.setProvider(CloudProvider.GCP);

        // Add BigQuery service
        com.dlap.discovery.common.config.CloudHierarchyProperties.ServiceConfig bigqueryService =
            new com.dlap.discovery.common.config.CloudHierarchyProperties.ServiceConfig();
        bigqueryService.setId("bigquery");
        bigqueryService.setDisplayName("BigQuery");

        // Add BigQuery resource types
        com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig datasetType =
            new com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig();
        datasetType.setId("gcp_bigquery_dataset");
        datasetType.setDisplayName("BigQuery Dataset");

        com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig tableType =
            new com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig();
        tableType.setId("gcp_bigquery_table");
        tableType.setDisplayName("BigQuery Table");

        bigqueryService.setResourceTypes(List.of(datasetType, tableType));

        // Add GCS service
        com.dlap.discovery.common.config.CloudHierarchyProperties.ServiceConfig gcsService =
            new com.dlap.discovery.common.config.CloudHierarchyProperties.ServiceConfig();
        gcsService.setId("gcs");
        gcsService.setDisplayName("Google Cloud Storage");

        // Add GCS resource types
        com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig bucketType =
            new com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig();
        bucketType.setId("gcp_gcs_bucket");
        bucketType.setDisplayName("Google Cloud Storage Bucket");

        com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig objectType =
            new com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig();
        objectType.setId("gcp_gcs_object");
        objectType.setDisplayName("Google Cloud Storage Object");

        gcsService.setResourceTypes(List.of(bucketType, objectType));

        gcpProvider.setServices(List.of(bigqueryService, gcsService));

        properties.setProviders(List.of(gcpProvider));

        return properties;
    }
}
