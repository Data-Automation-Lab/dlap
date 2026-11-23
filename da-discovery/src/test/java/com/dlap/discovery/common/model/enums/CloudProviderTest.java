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

package com.dlap.discovery.common.model.enums;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CloudProviderTest {

	@Test
	void testEnumValues() {
		// Verify all expected enum values exist
		// UNITY (standalone Unity Catalog, not Databricks-only) is the seventh value.
		assertEquals(7, CloudProvider.values().length, "Should have 7 cloud provider values");
		assertNotNull(CloudProvider.GCP);
		assertNotNull(CloudProvider.AWS);
		assertNotNull(CloudProvider.AZURE);
		assertNotNull(CloudProvider.DATABRICKS);
		assertNotNull(CloudProvider.OCI);
		assertNotNull(CloudProvider.UNITY);
		assertNotNull(CloudProvider.UNKNOWN);
	}

	@Test
	void testDisplayNames() {
		// Test display names for all providers
		assertEquals("Google Cloud Platform", CloudProvider.GCP.getDisplayName());
		assertEquals("Amazon Web Services", CloudProvider.AWS.getDisplayName());
		assertEquals("Microsoft Azure", CloudProvider.AZURE.getDisplayName());
		assertEquals("Databricks", CloudProvider.DATABRICKS.getDisplayName());
		assertEquals("Oracle Cloud Infrastructure", CloudProvider.OCI.getDisplayName());
		assertEquals("Unity Catalog", CloudProvider.UNITY.getDisplayName());
		assertEquals("Unknown", CloudProvider.UNKNOWN.getDisplayName());
	}

	@ParameterizedTest
	@CsvSource({
			"GCP, GCP",
			"AWS, AWS",
			"AZURE, AZURE",
			"DATABRICKS, DATABRICKS",
			"Oracle, OCI",
			"UNITY, UNITY",
			"gcp, GCP",
			"aws, AWS",
			"azure, AZURE",
			"databricks, DATABRICKS",
			"oracle, OCI",
			"unity, UNITY"
	})
	void testFromNameExactMatches(String input, CloudProvider expected) {
		assertEquals(expected, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@ValueSource(strings = { "GOOGLE", "Google Cloud", "GCP Platform" })
	void testFromNameGCPPartialMatches(String input) {
		assertEquals(CloudProvider.GCP, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@ValueSource(strings = { "AMAZON", "Amazon Web", "AWS Services" })
	void testFromNameAWSPartialMatches(String input) {
		assertEquals(CloudProvider.AWS, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@ValueSource(strings = { "MICROSOFT", "MS Azure", "Azure Cloud" })
	void testFromNameAzurePartialMatches(String input) {
		assertEquals(CloudProvider.AZURE, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@ValueSource(strings = { "DATABRICKS Platform", "DataBricks" })
	void testFromNameDatabricksPartialMatches(String input) {
		assertEquals(CloudProvider.DATABRICKS, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@ValueSource(strings = { "unknown provider", "foo" })
	void testFromNameUnknownProvider(String input) {
		assertEquals(CloudProvider.UNKNOWN, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "  " })
	void testFromNameWithNullOrEmpty(String input) {
		assertEquals(CloudProvider.UNKNOWN, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@ValueSource(strings = { "Unity Catalog", "UC", "uc", "OSS Unity" })
	void testFromNameUnityPartialMatches(String input) {
		assertEquals(CloudProvider.UNITY, CloudProvider.fromName(input));
	}

	@ParameterizedTest
	@ValueSource(strings = { "OCI", "Oracle" })
	void testFromNameOraclePartialMatches(String input) {
		assertEquals(CloudProvider.OCI, CloudProvider.fromName(input));
	}
}