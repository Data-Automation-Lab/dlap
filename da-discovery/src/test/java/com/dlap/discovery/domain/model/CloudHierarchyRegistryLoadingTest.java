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

package com.dlap.discovery.domain.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.config.CloudHierarchyProperties;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ProviderConfig;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ServiceConfig;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.gcp.ComputeResource;
import com.dlap.discovery.crawler.model.gcp.GcpResource;

/**
 * This test demonstrates how to use CloudHierarchyRegistry to load ResourceType
 * and
 * CloudService instances from configuration, rather than manually creating
 * them.
 */
class CloudHierarchyRegistryLoadingTest {

	private CloudHierarchyRegistry registry;

	@Mock
	private CloudHierarchyProperties mockProperties;

	// Test data
	private List<ProviderConfig> mockProviders;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		setupMockConfiguration();

		// Create and initialize the registry with our mock configuration
		registry = new CloudHierarchyRegistry(mockProperties);
		when(mockProperties.getProviders()).thenReturn(mockProviders);
		registry.initialize();
	}

	@Test
	void testLoadingAndUsingGCPComputeResourceType() {
		// Get resource type from registry instead of manually creating it
		ResourceType computeInstanceType = registry.getResourceType("gcp_compute_instance");

		// Verify the resource type was loaded correctly
		assertNotNull(computeInstanceType);
		assertEquals("gcp_compute_instance", computeInstanceType.id());
		assertEquals("Compute Instance", computeInstanceType.displayName());

		// Verify the service and provider hierarchy
		CloudService service = computeInstanceType.service();
		assertNotNull(service);
		assertEquals("gcp_compute", service.id());
		assertEquals("Compute Engine", service.displayName());
		assertEquals(CloudProvider.GCP, service.provider());

		// Now use the loaded resource type to create a resource
		GcpResource resource = new GcpResource(computeInstanceType, "instance-1", "Test Instance");

		// Verify the resource was created with the correct type
		assertEquals(computeInstanceType, resource.getResourceType());
		assertEquals("instance-1", resource.getResourceId());
		assertEquals("Test Instance", resource.getName());
		assertEquals(CloudProvider.GCP, resource.getResourceType().service().provider());
	}

	@Test
	void testLoadingAndUsingAWSS3ResourceType() {
		// Get resource type from registry
		ResourceType s3BucketType = registry.getResourceType("aws_s3_bucket");

		// Verify the resource type was loaded correctly
		assertNotNull(s3BucketType);
		assertEquals("aws_s3_bucket", s3BucketType.id());
		assertEquals("S3 Bucket", s3BucketType.displayName());

		// Verify the service and provider hierarchy
		CloudService service = s3BucketType.service();
		assertNotNull(service);
		assertEquals("aws_s3", service.id());
		assertEquals("Amazon S3", service.displayName());
		assertEquals(CloudProvider.AWS, service.provider());
	}

	@Test
	void testCreatingComputeResourceWithRegistryType() {
		// Get resource type from registry
		ResourceType computeInstanceType = registry.getResourceType("gcp_compute_instance");
		assertNotNull(computeInstanceType);

		// Create a compute-specific resource using the registry type
		ComputeResource computeResource = new ComputeResource(computeInstanceType, "instance-1", "Test Instance");

		// Set compute-specific properties
		computeResource.setMachineType("n1-standard-1");
		computeResource.setCpuCount(2);
		computeResource.setMemoryMb(4096);

		// Verify the properties
		assertEquals(computeInstanceType, computeResource.getResourceType());
		assertEquals("n1-standard-1", computeResource.getMachineType());
		assertEquals(2, computeResource.getCpuCount());
		assertEquals(4096, computeResource.getMemoryMb());

		// Also verify resource ID and name
		assertEquals("instance-1", computeResource.getResourceId());
		assertEquals("Test Instance", computeResource.getName());
	}

	@Test
	void testAccessingMultipleResourceTypesForService() {
		// Get the cloud service for BigQuery
		CloudService bigQueryService = registry.getService("gcp_bigquery");
		assertNotNull(bigQueryService);

		// Get all resource types for that service
		List<ResourceType> bigQueryTypes = registry.getResourceTypes(bigQueryService);

		// Verify we have the expected types
		assertEquals(2, bigQueryTypes.size());

		// Verify each type
		ResourceType datasetType = null;
		ResourceType tableType = null;

		for (ResourceType type : bigQueryTypes) {
			if (type.id().equals("gcp_bigquery_dataset")) {
				datasetType = type;
			} else if (type.id().equals("gcp_bigquery_table")) {
				tableType = type;
			}
		}

		assertNotNull(datasetType, "BigQuery Dataset type should be found");
		assertNotNull(tableType, "BigQuery Table type should be found");

		// Verify their properties
		assertEquals("BigQuery Dataset", datasetType.displayName());
		assertEquals("BigQuery Table", tableType.displayName());

		// Verify both types point to the same service
		assertSame(bigQueryService, datasetType.service());
		assertSame(bigQueryService, tableType.service());
	}

	@Test
	void testFindingAllResourceTypesForProvider() {
		// Get all GCP resource types
		List<ResourceType> gcpTypes = registry.getResourceTypes(CloudProvider.GCP);

		// There should be 4 types for GCP (2 for Compute, 2 for BigQuery)
		assertEquals(4, gcpTypes.size());

		// Verify we have the expected types by ID
		assertTrue(gcpTypes.stream().anyMatch(rt -> rt.id().equals("gcp_compute_instance")));
		assertTrue(gcpTypes.stream().anyMatch(rt -> rt.id().equals("gcp_compute_disk")));
		assertTrue(gcpTypes.stream().anyMatch(rt -> rt.id().equals("gcp_bigquery_dataset")));
		assertTrue(gcpTypes.stream().anyMatch(rt -> rt.id().equals("gcp_bigquery_table")));

		// Also verify all have the same provider
		for (ResourceType type : gcpTypes) {
			assertEquals(CloudProvider.GCP, type.service().provider());
		}
	}

	/**
	 * Sets up the mock configuration to mimic what would be in application.yml
	 */
	private void setupMockConfiguration() {
		mockProviders = new ArrayList<>();

		// ======== GCP Provider Configuration =========
		ProviderConfig gcpConfig = new ProviderConfig();
		gcpConfig.setProvider(CloudProvider.GCP);

		List<ServiceConfig> gcpServices = new ArrayList<>();

		// --- GCP Compute Service ---
		ServiceConfig gcpComputeService = new ServiceConfig();
		gcpComputeService.setId("gcp_compute");
		gcpComputeService.setDisplayName("Compute Engine");

		List<ResourceTypeConfig> gcpComputeTypes = new ArrayList<>();

		ResourceTypeConfig computeInstanceType = new ResourceTypeConfig();
		computeInstanceType.setId("gcp_compute_instance");
		computeInstanceType.setDisplayName("Compute Instance");
		gcpComputeTypes.add(computeInstanceType);

		ResourceTypeConfig computeDiskType = new ResourceTypeConfig();
		computeDiskType.setId("gcp_compute_disk");
		computeDiskType.setDisplayName("Compute Disk");
		gcpComputeTypes.add(computeDiskType);

		gcpComputeService.setResourceTypes(gcpComputeTypes);
		gcpServices.add(gcpComputeService);

		// --- GCP BigQuery Service ---
		ServiceConfig gcpBigQueryService = new ServiceConfig();
		gcpBigQueryService.setId("gcp_bigquery");
		gcpBigQueryService.setDisplayName("BigQuery");

		List<ResourceTypeConfig> gcpBigQueryTypes = new ArrayList<>();

		ResourceTypeConfig bigQueryDatasetType = new ResourceTypeConfig();
		bigQueryDatasetType.setId("gcp_bigquery_dataset");
		bigQueryDatasetType.setDisplayName("BigQuery Dataset");
		gcpBigQueryTypes.add(bigQueryDatasetType);

		ResourceTypeConfig bigQueryTableType = new ResourceTypeConfig();
		bigQueryTableType.setId("gcp_bigquery_table");
		bigQueryTableType.setDisplayName("BigQuery Table");
		gcpBigQueryTypes.add(bigQueryTableType);

		gcpBigQueryService.setResourceTypes(gcpBigQueryTypes);
		gcpServices.add(gcpBigQueryService);

		gcpConfig.setServices(gcpServices);
		mockProviders.add(gcpConfig);

		// ======== AWS Provider Configuration =========
		ProviderConfig awsConfig = new ProviderConfig();
		awsConfig.setProvider(CloudProvider.AWS);

		List<ServiceConfig> awsServices = new ArrayList<>();

		// --- AWS EC2 Service ---
		ServiceConfig awsEc2Service = new ServiceConfig();
		awsEc2Service.setId("aws_ec2");
		awsEc2Service.setDisplayName("Amazon EC2");

		List<ResourceTypeConfig> awsEc2Types = new ArrayList<>();

		ResourceTypeConfig ec2InstanceType = new ResourceTypeConfig();
		ec2InstanceType.setId("aws_ec2_instance");
		ec2InstanceType.setDisplayName("EC2 Instance");
		awsEc2Types.add(ec2InstanceType);

		awsEc2Service.setResourceTypes(awsEc2Types);
		awsServices.add(awsEc2Service);

		// --- AWS S3 Service ---
		ServiceConfig awsS3Service = new ServiceConfig();
		awsS3Service.setId("aws_s3");
		awsS3Service.setDisplayName("Amazon S3");

		List<ResourceTypeConfig> awsS3Types = new ArrayList<>();

		ResourceTypeConfig s3BucketType = new ResourceTypeConfig();
		s3BucketType.setId("aws_s3_bucket");
		s3BucketType.setDisplayName("S3 Bucket");
		awsS3Types.add(s3BucketType);

		awsS3Service.setResourceTypes(awsS3Types);
		awsServices.add(awsS3Service);

		awsConfig.setServices(awsServices);
		mockProviders.add(awsConfig);
	}
}