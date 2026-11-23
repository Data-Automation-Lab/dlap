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

package com.dlap.discovery.catalog.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.dlap.discovery.common.config.CloudHierarchyProperties;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ProviderConfig;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ServiceConfig;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;

class CloudHierarchyRegistryIntegrationTest {

	private CloudHierarchyRegistry registry;

	@Mock
	private CloudHierarchyProperties mockProperties;

	// Test data
	private List<ProviderConfig> mockProviders;
	private List<ServiceConfig> gcpServices;
	private List<ServiceConfig> awsServices;
	private List<ServiceConfig> azureServices;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		setupMockData();

		registry = new CloudHierarchyRegistry(mockProperties);
		when(mockProperties.getProviders()).thenReturn(mockProviders);
		registry.initialize();
	}

	@Test
	void testGetResourceType() {
		// Test retrieving resource types by ID
		ResourceType gcpComputeInstance = registry.getResourceType("gcp_compute_instance");
		ResourceType awsEc2Instance = registry.getResourceType("aws_ec2_instance");
		ResourceType azureVm = registry.getResourceType("azure_vm");

		// Verify we got the correct resource types
		assertNotNull(gcpComputeInstance);
		assertNotNull(awsEc2Instance);
		assertNotNull(azureVm);

		assertEquals("gcp_compute_instance", gcpComputeInstance.id());
		assertEquals("aws_ec2_instance", awsEc2Instance.id());
		assertEquals("azure_vm", azureVm.id());

		assertEquals("Google Compute Instance", gcpComputeInstance.displayName());
		assertEquals("EC2 Instance", awsEc2Instance.displayName());
		assertEquals("Azure Virtual Machine", azureVm.displayName());
	}

	@Test
	void testGetService() {
		// Test retrieving services by ID
		CloudService gcpCompute = registry.getService("gcp_compute");
		CloudService awsEc2 = registry.getService("aws_ec2");
		CloudService azureCompute = registry.getService("azure_compute");

		// Verify we got the correct services
		assertNotNull(gcpCompute);
		assertNotNull(awsEc2);
		assertNotNull(azureCompute);

		assertEquals("gcp_compute", gcpCompute.id());
		assertEquals("aws_ec2", awsEc2.id());
		assertEquals("azure_compute", azureCompute.id());

		assertEquals("Google Compute Engine", gcpCompute.displayName());
		assertEquals("Amazon EC2", awsEc2.displayName());
		assertEquals("Azure Compute", azureCompute.displayName());
	}

	@Test
	void testGetResourceTypes_byService() {
		// Get service first
		CloudService gcpCompute = registry.getService("gcp_compute");

		// Then get resource types for that service
		List<ResourceType> computeResourceTypes = registry.getResourceTypes(gcpCompute);

		// Verify the right resource types were returned
		assertEquals(2, computeResourceTypes.size());
		assertTrue(computeResourceTypes.stream().anyMatch(rt -> rt.id().equals("gcp_compute_instance")));
		assertTrue(computeResourceTypes.stream().anyMatch(rt -> rt.id().equals("gcp_compute_disk")));
	}

	@Test
	void testGetResourceTypes_byCloudProvider() {
		// Get all GCP resource types
		List<ResourceType> gcpResourceTypes = registry.getResourceTypes(CloudProvider.GCP);

		// Get all AWS resource types
		List<ResourceType> awsResourceTypes = registry.getResourceTypes(CloudProvider.AWS);

		// Verify the right number of resource types were returned
		assertEquals(4, gcpResourceTypes.size());
		assertEquals(3, awsResourceTypes.size());

		// Verify the resource types contain expected IDs
		assertTrue(gcpResourceTypes.stream().anyMatch(rt -> rt.id().equals("gcp_compute_instance")));
		assertTrue(gcpResourceTypes.stream().anyMatch(rt -> rt.id().equals("gcp_compute_disk")));
		assertTrue(gcpResourceTypes.stream().anyMatch(rt -> rt.id().equals("gcp_bigquery_dataset")));
		assertTrue(gcpResourceTypes.stream().anyMatch(rt -> rt.id().equals("gcp_bigquery_table")));

		assertTrue(awsResourceTypes.stream().anyMatch(rt -> rt.id().equals("aws_ec2_instance")));
		assertTrue(awsResourceTypes.stream().anyMatch(rt -> rt.id().equals("aws_s3_bucket")));
		assertTrue(awsResourceTypes.stream().anyMatch(rt -> rt.id().equals("aws_rds_instance")));
	}

	@Test
	void testGetServices_byCloudProvider() {
		// Get all GCP services
		List<CloudService> gcpServices = registry.getServices(CloudProvider.GCP);

		// Get all AWS services
		List<CloudService> awsServices = registry.getServices(CloudProvider.AWS);

		// Verify the right number of services were returned
		assertEquals(2, gcpServices.size());
		assertEquals(3, awsServices.size());

		// Verify the services contain expected IDs
		assertTrue(gcpServices.stream().anyMatch(s -> s.id().equals("gcp_compute")));
		assertTrue(gcpServices.stream().anyMatch(s -> s.id().equals("gcp_bigquery")));

		assertTrue(awsServices.stream().anyMatch(s -> s.id().equals("aws_ec2")));
		assertTrue(awsServices.stream().anyMatch(s -> s.id().equals("aws_s3")));
		assertTrue(awsServices.stream().anyMatch(s -> s.id().equals("aws_rds")));
	}

	@Test
	void testGetAllServices() {
		// Get all services
		assertEquals(6, registry.getAllServices().size());
	}

	@Test
	void testGetAllResourceTypes() {
		// Get all resource types
		assertEquals(8, registry.getAllResourceTypes().size());
	}

	@Test
	void testFullHierarchyAccess() {
		// Test that we can navigate the full hierarchy from ResourceType to
		// CloudProvider
		ResourceType gcpComputeInstance = registry.getResourceType("gcp_compute_instance");

		CloudService service = gcpComputeInstance.service();
		CloudProvider provider = service.provider();

		assertEquals("gcp_compute", service.id());
		assertEquals(CloudProvider.GCP, provider);
	}

	@Test
	void testNonExistentResourceType() {
		// Test behavior when requesting a non-existent resource type
		ResourceType nonExistentType = registry.getResourceType("non_existent_type");
		assertNull(nonExistentType);
	}

	@Test
	void testNonExistentService() {
		// Test behavior when requesting a non-existent service
		CloudService nonExistentService = registry.getService("non_existent_service");
		assertNull(nonExistentService);
	}

	private void setupMockData() {
		// Set up GCP services
		gcpServices = new ArrayList<>();
		ServiceConfig gcpCompute = new ServiceConfig();
		gcpCompute.setId("gcp_compute");
		gcpCompute.setDisplayName("Google Compute Engine");

		List<ResourceTypeConfig> gcpComputeTypes = new ArrayList<>();
		ResourceTypeConfig gcpComputeInstance = new ResourceTypeConfig();
		gcpComputeInstance.setId("gcp_compute_instance");
		gcpComputeInstance.setDisplayName("Google Compute Instance");
		gcpComputeTypes.add(gcpComputeInstance);

		ResourceTypeConfig gcpComputeDisk = new ResourceTypeConfig();
		gcpComputeDisk.setId("gcp_compute_disk");
		gcpComputeDisk.setDisplayName("Google Compute Disk");
		gcpComputeTypes.add(gcpComputeDisk);

		gcpCompute.setResourceTypes(gcpComputeTypes);
		gcpServices.add(gcpCompute);

		// Add GCP BigQuery
		ServiceConfig gcpBigQuery = new ServiceConfig();
		gcpBigQuery.setId("gcp_bigquery");
		gcpBigQuery.setDisplayName("Google BigQuery");

		List<ResourceTypeConfig> gcpBigQueryTypes = new ArrayList<>();
		ResourceTypeConfig gcpBigQueryDataset = new ResourceTypeConfig();
		gcpBigQueryDataset.setId("gcp_bigquery_dataset");
		gcpBigQueryDataset.setDisplayName("BigQuery Dataset");
		gcpBigQueryTypes.add(gcpBigQueryDataset);

		ResourceTypeConfig gcpBigQueryTable = new ResourceTypeConfig();
		gcpBigQueryTable.setId("gcp_bigquery_table");
		gcpBigQueryTable.setDisplayName("BigQuery Table");
		gcpBigQueryTypes.add(gcpBigQueryTable);

		gcpBigQuery.setResourceTypes(gcpBigQueryTypes);
		gcpServices.add(gcpBigQuery);

		// Set up AWS services
		awsServices = new ArrayList<>();

		// EC2
		ServiceConfig awsEc2 = new ServiceConfig();
		awsEc2.setId("aws_ec2");
		awsEc2.setDisplayName("Amazon EC2");

		List<ResourceTypeConfig> awsEc2Types = new ArrayList<>();
		ResourceTypeConfig awsEc2Instance = new ResourceTypeConfig();
		awsEc2Instance.setId("aws_ec2_instance");
		awsEc2Instance.setDisplayName("EC2 Instance");
		awsEc2Types.add(awsEc2Instance);

		awsEc2.setResourceTypes(awsEc2Types);
		awsServices.add(awsEc2);

		// S3
		ServiceConfig awsS3 = new ServiceConfig();
		awsS3.setId("aws_s3");
		awsS3.setDisplayName("Amazon S3");

		List<ResourceTypeConfig> awsS3Types = new ArrayList<>();
		ResourceTypeConfig awsS3Bucket = new ResourceTypeConfig();
		awsS3Bucket.setId("aws_s3_bucket");
		awsS3Bucket.setDisplayName("S3 Bucket");
		awsS3Types.add(awsS3Bucket);

		awsS3.setResourceTypes(awsS3Types);
		awsServices.add(awsS3);

		// RDS
		ServiceConfig awsRds = new ServiceConfig();
		awsRds.setId("aws_rds");
		awsRds.setDisplayName("Amazon RDS");

		List<ResourceTypeConfig> awsRdsTypes = new ArrayList<>();
		ResourceTypeConfig awsRdsInstance = new ResourceTypeConfig();
		awsRdsInstance.setId("aws_rds_instance");
		awsRdsInstance.setDisplayName("RDS Instance");
		awsRdsTypes.add(awsRdsInstance);

		awsRds.setResourceTypes(awsRdsTypes);
		awsServices.add(awsRds);

		// Set up Azure services
		azureServices = new ArrayList<>();
		ServiceConfig azureCompute = new ServiceConfig();
		azureCompute.setId("azure_compute");
		azureCompute.setDisplayName("Azure Compute");

		List<ResourceTypeConfig> azureComputeTypes = new ArrayList<>();
		ResourceTypeConfig azureVm = new ResourceTypeConfig();
		azureVm.setId("azure_vm");
		azureVm.setDisplayName("Azure Virtual Machine");
		azureComputeTypes.add(azureVm);

		azureCompute.setResourceTypes(azureComputeTypes);
		azureServices.add(azureCompute);

		// Create provider configurations
		mockProviders = new ArrayList<>();

		ProviderConfig gcpConfig = new ProviderConfig();
		gcpConfig.setProvider(CloudProvider.GCP);
		gcpConfig.setServices(gcpServices);
		mockProviders.add(gcpConfig);

		ProviderConfig awsConfig = new ProviderConfig();
		awsConfig.setProvider(CloudProvider.AWS);
		awsConfig.setServices(awsServices);
		mockProviders.add(awsConfig);

		ProviderConfig azureConfig = new ProviderConfig();
		azureConfig.setProvider(CloudProvider.AZURE);
		azureConfig.setServices(azureServices);
		mockProviders.add(azureConfig);
	}
}