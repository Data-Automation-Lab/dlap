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

package com.dlap.discovery.sd.domain.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
import com.fasterxml.jackson.databind.ObjectMapper;

class CloudHierarchySerializationTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

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
	void testJacksonSerializationOfResourceType() throws Exception {
		// Get resource type from registry instead of manually creating it
		ResourceType resourceType = registry.getResourceType("gcp_compute_instance");
		assertNotNull(resourceType, "ResourceType should be loaded from registry");

		// Serialize to JSON
		String json = objectMapper.writeValueAsString(resourceType);

		// Deserialize from JSON
		ResourceType deserialized = objectMapper.readValue(json, ResourceType.class);

		// Verify deserialized object
		assertEquals(resourceType.id(), deserialized.id());
		assertEquals(resourceType.displayName(), deserialized.displayName());
		assertEquals(resourceType.service().id(), deserialized.service().id());
		assertEquals(resourceType.service().displayName(), deserialized.service().displayName());
		assertEquals(resourceType.service().provider(), deserialized.service().provider());
	}

	@Test
	void testJacksonSerializationOfCloudService() throws Exception {
		// Get service from registry
		CloudService service = registry.getService("aws_ec2");
		assertNotNull(service, "Service should be loaded from registry");

		// Serialize to JSON
		String json = objectMapper.writeValueAsString(service);

		// Deserialize from JSON
		CloudService deserialized = objectMapper.readValue(json, CloudService.class);

		// Verify deserialized object
		assertEquals(service.id(), deserialized.id());
		assertEquals(service.displayName(), deserialized.displayName());
		assertEquals(service.provider(), deserialized.provider());
	}

	@Test
	void testJacksonSerializationOfCloudProvider() throws Exception {
		// Use a cloud provider from the registry
		CloudProvider provider = registry.getService("gcp_compute").provider();
		assertEquals(CloudProvider.GCP, provider);

		// Serialize provider to JSON
		String json = objectMapper.writeValueAsString(provider);

		// Deserialize from JSON
		CloudProvider deserialized = objectMapper.readValue(json, CloudProvider.class);

		// Verify deserialized enum
		assertEquals(provider, deserialized);
	}

	@Test
	void testNestingInJson() throws Exception {
		// Get resources from registry
		ResourceType resourceType = registry.getResourceType("aws_s3_bucket");
		assertNotNull(resourceType, "ResourceType should be loaded from registry");

		// Create a wrapper object to test nested serialization
		TestWrapper wrapper = new TestWrapper("test-wrapper", resourceType);

		// Serialize to JSON
		String json = objectMapper.writeValueAsString(wrapper);

		// Deserialize from JSON
		TestWrapper deserialized = objectMapper.readValue(json, TestWrapper.class);

		// Verify
		assertEquals(wrapper.getName(), deserialized.getName());
		assertNotNull(deserialized.getResourceType());
		assertEquals(resourceType.id(), deserialized.getResourceType().id());
		assertEquals(resourceType.service().id(), deserialized.getResourceType().service().id());
		assertEquals(resourceType.service().provider(), deserialized.getResourceType().service().provider());
	}

	// A simple class to test nested serialization
	private static class TestWrapper {
		private String name;
		private ResourceType resourceType;

		// Default constructor for Jackson
		public TestWrapper() {
		}

		public TestWrapper(String name, ResourceType resourceType) {
			this.name = name;
			this.resourceType = resourceType;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public ResourceType getResourceType() {
			return resourceType;
		}

		public void setResourceType(ResourceType resourceType) {
			this.resourceType = resourceType;
		}
	}

	// Helper methods for Java serialization
	private byte[] serialize(Object obj) throws IOException {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
				ObjectOutputStream oos = new ObjectOutputStream(baos)) {
			oos.writeObject(obj);
			return baos.toByteArray();
		}
	}

	private Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
		try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
				ObjectInputStream ois = new ObjectInputStream(bais)) {
			return ois.readObject();
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
		gcpComputeService.setDisplayName("Google Compute Engine");

		List<ResourceTypeConfig> gcpComputeTypes = new ArrayList<>();

		ResourceTypeConfig computeInstanceType = new ResourceTypeConfig();
		computeInstanceType.setId("gcp_compute_instance");
		computeInstanceType.setDisplayName("Compute Instance");
		gcpComputeTypes.add(computeInstanceType);

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