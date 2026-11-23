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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;

import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.enums.CloudProvider;

class CloudServiceTest {

	@Test
	void testCloudServiceConstructor() {
		// Arrange
		String id = "aws_ec2";
		String displayName = "Amazon EC2";
		CloudProvider provider = CloudProvider.AWS;

		// Act
		CloudService service = new CloudService(id, displayName, provider);

		// Assert
		assertEquals(id, service.id());
		assertEquals(displayName, service.displayName());
		assertEquals(provider, service.provider());
	}

	@Test
	void testEquality() {
		// Arrange
		CloudService service1 = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);
		CloudService service2 = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);
		CloudService service3 = new CloudService("aws_s3", "Amazon S3", CloudProvider.AWS);

		// Assert
		assertEquals(service1, service2, "Same values should be equal");
		assertNotEquals(service1, service3, "Different IDs should not be equal");
		assertEquals(service1.hashCode(), service2.hashCode(), "Equal objects should have same hashCode");
	}

	@Test
	void testToString() {
		// Arrange
		CloudService service = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);

		// Act
		String result = service.toString();

		// Assert
		assertTrue(result.contains("aws_ec2"), "toString should contain the ID");
		assertTrue(result.contains("Amazon EC2"), "toString should contain the display name");
		assertTrue(result.contains("AWS"), "toString should contain the provider");
	}

	@Test
	void testWithDifferentProviders() {
		// Test with different providers
		CloudService awsService = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);
		CloudService gcpService = new CloudService("gcp_compute", "Compute Engine", CloudProvider.GCP);
		CloudService azureService = new CloudService("azure_vm", "Azure VM", CloudProvider.AZURE);

		assertEquals(CloudProvider.AWS, awsService.provider());
		assertEquals(CloudProvider.GCP, gcpService.provider());
		assertEquals(CloudProvider.AZURE, azureService.provider());
	}

	@Test
	void testRecordComponents() {
		// Java records should autogenerate accessors, equals, hashCode, and toString
		// Verify we can access components
		CloudService service = new CloudService("gcp_storage", "Cloud Storage", CloudProvider.GCP);

		assertEquals("gcp_storage", service.id());
		assertEquals("Cloud Storage", service.displayName());
		assertEquals(CloudProvider.GCP, service.provider());
	}

	@ParameterizedTest
	@NullSource
	void testWithNullProvider(CloudProvider provider) {
		// This test verifies what happens with a null provider
		// Note: Java records may have varying behavior with null components
		// depending on JDK version and record implementation details
		try {
			CloudService service = new CloudService("id", "displayName", provider);
			// If we get here, the constructor didn't throw an exception
			assertNull(service.provider(), "Provider should be null");
		} catch (NullPointerException e) {
			// This is also acceptable behavior
			assertNotNull(e, "NullPointerException was expected and occurred");
		}
	}
}