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

package com.dlap.discovery.catalog.persistence.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultResourceCrawlerRegistryTest {

    @Mock
    private CloudHierarchyRegistry hierarchyRegistry;

    @Mock
    private IResourceCrawler<CloudResource> awsCrawler;

    @Mock
    private IResourceCrawler<CloudResource> gcpCrawler;

    private DefaultResourceCrawlerRegistry registry;

    private ResourceType awsEc2Type;
    private ResourceType awsS3Type;
    private ResourceType gcpComputeType;
    private CloudService awsEc2Service;
    private CloudService awsS3Service;
    private CloudService gcpComputeService;

    @BeforeEach
    void setUp() {
        registry = new DefaultResourceCrawlerRegistry(hierarchyRegistry);

        // Create sample resource types and services
        awsEc2Service = new CloudService("aws-ec2", "Amazon EC2", CloudProvider.AWS);
        awsS3Service = new CloudService("aws-s3", "Amazon S3", CloudProvider.AWS);
        gcpComputeService = new CloudService("gcp-compute", "Google Compute Engine", CloudProvider.GCP);

        awsEc2Type = new ResourceType("aws-ec2-instance", "EC2 Instance", awsEc2Service);
        awsS3Type = new ResourceType("aws-s3-bucket", "S3 Bucket", awsS3Service);
        gcpComputeType = new ResourceType("gcp-compute-instance", "GCP VM Instance", gcpComputeService);

        // Configure mocks with lenient stubs to avoid UnnecessaryStubbingException
        lenient().when(awsCrawler.getSupportedResourceTypes()).thenReturn(Arrays.asList(awsEc2Type, awsS3Type));
        lenient().when(awsCrawler.getProvider()).thenReturn(CloudProvider.AWS);

        lenient().when(gcpCrawler.getSupportedResourceTypes()).thenReturn(Collections.singletonList(gcpComputeType));
        lenient().when(gcpCrawler.getProvider()).thenReturn(CloudProvider.GCP);

        lenient().when(hierarchyRegistry.getResourceTypes(awsEc2Service)).thenReturn(Collections.singletonList(awsEc2Type));
        lenient().when(hierarchyRegistry.getResourceTypes(awsS3Service)).thenReturn(Collections.singletonList(awsS3Type));
        lenient().when(hierarchyRegistry.getResourceTypes(gcpComputeService)).thenReturn(Collections.singletonList(gcpComputeType));

        lenient().when(hierarchyRegistry.getServices(CloudProvider.AWS)).thenReturn(Arrays.asList(awsEc2Service, awsS3Service));
        lenient().when(hierarchyRegistry.getServices(CloudProvider.GCP)).thenReturn(Collections.singletonList(gcpComputeService));
    }

    @Test
    void testRegisterCrawler() {
        assertTrue(registry.registerCrawler(awsCrawler));
        assertSame(awsCrawler, registry.getCrawler(awsEc2Type));
        assertSame(awsCrawler, registry.getCrawler(awsS3Type));
    }

    @Test
    void testRegisterNullCrawler() {
        assertFalse(registry.registerCrawler(null));
    }

    @Test
    void testRegisterCrawlerWithNoTypes() {
        IResourceCrawler<CloudResource> crawlerWithNoTypes = mock(IResourceCrawler.class);
        when(crawlerWithNoTypes.getSupportedResourceTypes()).thenReturn(Collections.emptyList());

        assertFalse(registry.registerCrawler(crawlerWithNoTypes));
    }

    @Test
    void testRegisterCrawlerReplacesExisting() {
        registry.registerCrawler(awsCrawler);

        IResourceCrawler<CloudResource> newAwsCrawler = mock(IResourceCrawler.class);
        when(newAwsCrawler.getSupportedResourceTypes()).thenReturn(Collections.singletonList(awsEc2Type));

        assertTrue(registry.registerCrawler(newAwsCrawler));
        assertSame(newAwsCrawler, registry.getCrawler(awsEc2Type));
        assertSame(awsCrawler, registry.getCrawler(awsS3Type)); // Should still be registered for this type
    }

    @Test
    void testUnregisterCrawler() {
        registry.registerCrawler(awsCrawler);
        registry.registerCrawler(gcpCrawler);

        assertTrue(registry.unregisterCrawler(awsCrawler));
        assertNull(registry.getCrawler(awsEc2Type));
        assertNull(registry.getCrawler(awsS3Type));
        assertSame(gcpCrawler, registry.getCrawler(gcpComputeType)); // Should still be registered
    }

    @Test
    void testUnregisterNullCrawler() {
        assertFalse(registry.unregisterCrawler(null));
    }

    @Test
    void testUnregisterNonExistentCrawler() {
        IResourceCrawler<CloudResource> unregisteredCrawler = mock(IResourceCrawler.class);
        lenient().when(unregisteredCrawler.getSupportedResourceTypes()).thenReturn(Collections.singletonList(awsEc2Type));

        assertFalse(registry.unregisterCrawler(unregisteredCrawler));
    }

    @Test
    void testGetCrawlerWithNullResourceType() {
        assertNull(registry.getCrawler(null));
    }

    @Test
    void testGetCrawlersForService() {
        registry.registerCrawler(awsCrawler);
        registry.registerCrawler(gcpCrawler);

        List<IResourceCrawler<?>> ec2Crawlers = registry.getCrawlers(awsEc2Service);
        assertEquals(1, ec2Crawlers.size());
        assertSame(awsCrawler, ec2Crawlers.get(0));

        List<IResourceCrawler<?>> gcpCrawlers = registry.getCrawlers(gcpComputeService);
        assertEquals(1, gcpCrawlers.size());
        assertSame(gcpCrawler, gcpCrawlers.get(0));
    }

    @Test
    void testGetCrawlersForNullService() {
        assertTrue(registry.getCrawlers((CloudService) null).isEmpty());
    }

    @Test
    void testGetCrawlersForProvider() {
        registry.registerCrawler(awsCrawler);
        registry.registerCrawler(gcpCrawler);

        List<IResourceCrawler<?>> awsCrawlers = registry.getCrawlers(CloudProvider.AWS);
        assertEquals(1, awsCrawlers.size());
        assertSame(awsCrawler, awsCrawlers.get(0));

        List<IResourceCrawler<?>> gcpCrawlers = registry.getCrawlers(CloudProvider.GCP);
        assertEquals(1, gcpCrawlers.size());
        assertSame(gcpCrawler, gcpCrawlers.get(0));
    }

    @Test
    void testGetCrawlersForNullProvider() {
        assertTrue(registry.getCrawlers((CloudProvider) null).isEmpty());
    }

    @Test
    void testGetCrawlersForProviderTyped() {
        registry.registerCrawler(awsCrawler);

        Collection<IResourceCrawler<CloudResource>> awsCrawlers = registry.getCrawlersForProvider(CloudProvider.AWS);
        assertEquals(1, awsCrawlers.size());
        assertTrue(awsCrawlers.contains(awsCrawler));
    }

    @Test
    void testGetCrawlersForTypesFiltered() {
        registry.registerCrawler(awsCrawler);
        registry.registerCrawler(gcpCrawler);

        Collection<IResourceCrawler<CloudResource>> ec2Crawlers = registry.getCrawlersForTypes(
            CloudProvider.AWS,
            Collections.singletonList(awsEc2Type)
        );

        assertEquals(1, ec2Crawlers.size());
        assertTrue(ec2Crawlers.contains(awsCrawler));
    }

    @Test
    void testGetCrawlersForTypesWithNullParams() {
        assertTrue(registry.getCrawlersForTypes(null, Arrays.asList(awsEc2Type)).isEmpty());
        assertTrue(registry.getCrawlersForTypes(CloudProvider.AWS, null).isEmpty());
        assertTrue(registry.getCrawlersForTypes(CloudProvider.AWS, Collections.emptyList()).isEmpty());
    }

    @Test
    void testGetAllCrawlers() {
        assertTrue(registry.getAllCrawlers().isEmpty());

        registry.registerCrawler(awsCrawler);
        registry.registerCrawler(gcpCrawler);

        List<IResourceCrawler<?>> allCrawlers = registry.getAllCrawlers();
        assertEquals(2, allCrawlers.size());
        assertTrue(allCrawlers.contains(awsCrawler));
        assertTrue(allCrawlers.contains(gcpCrawler));
    }

    @Test
    void testCrawlerRegisteredForMultipleTypesAppearsOnceInGetAllCrawlers() {
        registry.registerCrawler(awsCrawler); // Registered for 2 types

        List<IResourceCrawler<?>> allCrawlers = registry.getAllCrawlers();
        assertEquals(1, allCrawlers.size());
    }
}
