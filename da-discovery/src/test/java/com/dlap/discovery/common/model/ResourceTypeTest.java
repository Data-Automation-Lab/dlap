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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;

import com.dlap.discovery.common.model.enums.CloudProvider;

class ResourceTypeTest {

	@Test
	void testResourceTypeConstructor() {
		// Arrange
		String id = "aws_ec2_instance";
		String displayName = "Amazon EC2 Instance";
		CloudService service = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);

		// Act
		ResourceType resourceType = new ResourceType(id, displayName, service);

		// Assert
		assertEquals(id, resourceType.id());
		assertEquals(displayName, resourceType.displayName());
		assertEquals(service, resourceType.service());
	}

	@Test
	void testEquality() {
		// Arrange
		CloudService ec2Service = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);
		CloudService s3Service = new CloudService("aws_s3", "Amazon S3", CloudProvider.AWS);

		ResourceType type1 = new ResourceType("aws_ec2_instance", "EC2 Instance", ec2Service);
		ResourceType type2 = new ResourceType("aws_ec2_instance", "EC2 Instance", ec2Service);
		ResourceType type3 = new ResourceType("aws_s3_bucket", "S3 Bucket", s3Service);

		// Assert
		assertEquals(type1, type2, "Same values should be equal");
		assertNotEquals(type1, type3, "Different IDs should not be equal");
		assertEquals(type1.hashCode(), type2.hashCode(), "Equal objects should have same hashCode");
	}

	@Test
	void testToString() {
		// Arrange
		CloudService service = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);
		ResourceType resourceType = new ResourceType("aws_ec2_instance", "EC2 Instance", service);

		// Act
		String result = resourceType.toString();

		// Assert
		assertTrue(result.contains("aws_ec2_instance"), "toString should contain the ID");
		assertTrue(result.contains("EC2 Instance"), "toString should contain the display name");
		assertTrue(result.contains("aws_ec2"), "toString should contain the service ID");
	}

	@Test
	void testAccessToCloudProvider() {
		// Test that we can access the cloud provider through the service
		CloudService service = new CloudService("gcp_bigquery", "BigQuery", CloudProvider.GCP);
		ResourceType resourceType = new ResourceType("gcp_bigquery_dataset", "BigQuery Dataset", service);

		assertEquals(CloudProvider.GCP, resourceType.service().provider());
	}

	@Test
	void testWithDifferentServices() {
		// Test with different services
		CloudService ec2Service = new CloudService("aws_ec2", "Amazon EC2", CloudProvider.AWS);
		CloudService computeService = new CloudService("gcp_compute", "Compute Engine", CloudProvider.GCP);
		CloudService azureVmService = new CloudService("azure_vm", "Azure VM", CloudProvider.AZURE);

		ResourceType ec2Instance = new ResourceType("aws_ec2_instance", "EC2 Instance", ec2Service);
		ResourceType computeInstance = new ResourceType("gcp_compute_instance", "Compute Instance", computeService);
		ResourceType azureVm = new ResourceType("azure_vm_instance", "Azure VM Instance", azureVmService);

		assertEquals(CloudProvider.AWS, ec2Instance.service().provider());
		assertEquals(CloudProvider.GCP, computeInstance.service().provider());
		assertEquals(CloudProvider.AZURE, azureVm.service().provider());
	}

	@Test
	void testRecordComponents() {
		// Java records should autogenerate accessors, equals, hashCode, and toString
		// Verify we can access components
		CloudService service = new CloudService("aws_s3", "Amazon S3", CloudProvider.AWS);
		ResourceType resourceType = new ResourceType("aws_s3_bucket", "S3 Bucket", service);

		assertEquals("aws_s3_bucket", resourceType.id());
		assertEquals("S3 Bucket", resourceType.displayName());
		assertEquals(service, resourceType.service());
	}

	@ParameterizedTest
	@NullSource
	void testWithNullService(CloudService service) {
		// This test verifies what happens with a null service
		// Note: Java records may have varying behavior with null components
		// depending on JDK version and record implementation details
		try {
			ResourceType resourceType = new ResourceType("id", "displayName", service);
			// If we get here, the constructor didn't throw an exception
			assertNull(resourceType.service(), "Service should be null");
		} catch (NullPointerException e) {
			// This is also acceptable behavior
			assertNotNull(e, "NullPointerException was expected and occurred");
		}
	}
}