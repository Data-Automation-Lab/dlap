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

package com.dlap.discovery.common.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.config.CloudHierarchyProperties;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ProviderConfig;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ResourceTypeConfig;
import com.dlap.discovery.common.config.CloudHierarchyProperties.ServiceConfig;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.gcp.ComputeResource;
import com.dlap.discovery.crawler.model.gcp.GcpResource;

class CloudResourceIntegrationTest {

	private CloudHierarchyRegistry registry;

	@Mock
	private CloudHierarchyProperties mockProperties;

	// Test data
	private List<ProviderConfig> mockProviders;
	private ResourceType computeInstanceType;
	private ResourceType computeDiskType;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		setupMockConfiguration();

		// Create and initialize the registry with our mock configuration
		registry = new CloudHierarchyRegistry(mockProperties);
		when(mockProperties.getProviders()).thenReturn(mockProviders);
		registry.initialize();

		// Get the resource types from the registry
		computeInstanceType = registry.getResourceType("gcp_compute_instance");
		computeDiskType = registry.getResourceType("gcp_compute_disk");

		assertNotNull(computeInstanceType, "Compute instance type should be available in registry");
		assertNotNull(computeDiskType, "Compute disk type should be available in registry");
	}

	@Test
	void testCreateGCPResourceWithResourceType() {
		// Create a GCP resource with our resource type from registry
		GcpResource resource = new GcpResource(computeInstanceType, "instance-1", "Test Instance");

		// Verify that the resource was created with the correct resource type
		assertEquals(computeInstanceType, resource.getResourceType());
		assertEquals("instance-1", resource.getResourceId());
		assertEquals("Test Instance", resource.getName());

		// Verify the provider is accessible through the resource type
		assertEquals(CloudProvider.GCP, resource.getResourceType().service().provider());
	}

	@Test
	void testCreateComputeResourceWithResourceType() {
		// Create a compute resource with our resource type from registry
		ComputeResource computeResource = new ComputeResource(computeInstanceType, "instance-1", "Test Instance");

		// Verify the resource was created with the correct resource type
		assertEquals(computeInstanceType, computeResource.getResourceType());
		assertEquals("instance-1", computeResource.getResourceId());
		assertEquals("Test Instance", computeResource.getName());

		// Set some compute-specific properties
		computeResource.setMachineType("n1-standard-1");
		computeResource.setCpuCount(1);
		computeResource.setMemoryMb(3840);

		// Verify the properties were set
		assertEquals("n1-standard-1", computeResource.getMachineType());
		assertEquals(1, computeResource.getCpuCount());
		assertEquals(3840, computeResource.getMemoryMb());

		// Also verify the provider info is accessible
		assertEquals(CloudProvider.GCP, computeResource.getResourceType().service().provider());
	}

	@Test
	void testResourceTags() {
		// Create a resource with resource type from registry
		GcpResource resource = new GcpResource(computeInstanceType, "instance-1", "Test Instance");

		// Add tags using the Map interface
		Map<String, String> tags = resource.getTags();
		tags.put("environment", "production");
		tags.put("owner", "data-team");

		// Verify tags
		assertTrue(tags.containsKey("environment"));
		assertTrue(tags.containsKey("owner"));
		assertEquals("production", tags.get("environment"));
		assertEquals("data-team", tags.get("owner"));
		assertEquals(2, tags.size());
	}

	@Test
	void testResourceProperties() {
		// Create a resource with resource type from registry
		GcpResource resource = new GcpResource(computeInstanceType, "instance-1", "Test Instance");

		// Add properties using the Map interface
		Map<String, Object> properties = resource.getProperties();
		properties.put("ip_address", "10.0.0.1");
		properties.put("boot_disk_size_gb", 10);

		// Verify properties
		assertTrue(properties.containsKey("ip_address"));
		assertTrue(properties.containsKey("boot_disk_size_gb"));
		assertEquals("10.0.0.1", properties.get("ip_address"));
		assertEquals(10, properties.get("boot_disk_size_gb"));
		assertEquals(2, properties.size());
	}

	@Test
	void testResourceMetadata() {
		// Create a resource with resource type from registry
		GcpResource resource = new GcpResource(computeInstanceType, "instance-1", "Test Instance");

		// Set metadata
		resource.setRegion("us-central1");
		resource.setZone("us-central1-a");
		resource.setProjectId("test-project");
		resource.setCreatedAt(Instant.parse("2023-01-01T00:00:00Z"));
		resource.setLastDiscoveredAt(Instant.now());

		// Verify metadata
		assertEquals("us-central1", resource.getRegion());
		assertEquals("us-central1-a", resource.getZone());
		assertEquals("test-project", resource.getProjectId());
		assertEquals(Instant.parse("2023-01-01T00:00:00Z"), resource.getCreatedAt());
		assertNotNull(resource.getLastDiscoveredAt());
	}

	@Test
	void testValidationForCorrectResourceType() {
		// Attempting to create a GCP resource with a GCP resource type should work
		assertDoesNotThrow(() -> {
			new GcpResource(computeInstanceType, "instance-1", "Test Instance");
		});
	}

	@Test
	void testValidationForIncorrectResourceType() {
		// Get an AWS resource type from the registry
		ResourceType awsEc2InstanceType = registry.getResourceType("aws_ec2_instance");
		assertNotNull(awsEc2InstanceType, "AWS EC2 instance type should be available in registry");

		// Attempting to create a GCP resource with an AWS resource type should throw
		assertThrows(IllegalArgumentException.class, () -> {
			new GcpResource(awsEc2InstanceType, "instance-1", "Test Instance");
		});
	}

	@Test
	void testCloudProviderAccessThroughResourceType() {
		// Create a resource with resource type from registry
		GcpResource resource = new GcpResource(computeInstanceType, "instance-1", "Test Instance");

		// Access cloud provider through resource type
		CloudProvider provider = resource.getResourceType().service().provider();

		// Verify it's the correct provider
		assertEquals(CloudProvider.GCP, provider);
		assertEquals("Google Cloud Platform", provider.getDisplayName());
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

		ResourceTypeConfig computeInstanceTypeConfig = new ResourceTypeConfig();
		computeInstanceTypeConfig.setId("gcp_compute_instance");
		computeInstanceTypeConfig.setDisplayName("Compute Instance");
		gcpComputeTypes.add(computeInstanceTypeConfig);

		ResourceTypeConfig computeDiskTypeConfig = new ResourceTypeConfig();
		computeDiskTypeConfig.setId("gcp_compute_disk");
		computeDiskTypeConfig.setDisplayName("Compute Disk");
		gcpComputeTypes.add(computeDiskTypeConfig);

		gcpComputeService.setResourceTypes(gcpComputeTypes);
		gcpServices.add(gcpComputeService);

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

		ResourceTypeConfig ec2InstanceTypeConfig = new ResourceTypeConfig();
		ec2InstanceTypeConfig.setId("aws_ec2_instance");
		ec2InstanceTypeConfig.setDisplayName("EC2 Instance");
		awsEc2Types.add(ec2InstanceTypeConfig);

		awsEc2Service.setResourceTypes(awsEc2Types);
		awsServices.add(awsEc2Service);

		awsConfig.setServices(awsServices);
		mockProviders.add(awsConfig);
	}
}