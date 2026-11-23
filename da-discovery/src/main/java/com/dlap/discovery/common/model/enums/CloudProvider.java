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
     
/**
 * Represents the supported cloud providers.
 */
public enum CloudProvider {
	GCP, // Google Cloud Platform
	AWS, // Amazon Web Services
	AZURE, // Microsoft Azure
	OCI, // Oracle Cloud Infrastructure
	DATABRICKS, // Databricks
	UNITY, // Unity Catalog (standalone OSS metastore - not Databricks-only)
	// Add other providers as needed
	UNKNOWN // For resources where provider isn't identified
	;

	/**
	 * Converts a string name to the corresponding CloudProvider enum value.
	 * Performs case-insensitive matching and handles partial matches.
	 *
	 * @param input The string to convert to a CloudProvider
	 * @return The matching CloudProvider or UNKNOWN if no match is found
	 */
	public static CloudProvider fromName(String input) {
		if (input == null || input.trim().isEmpty()) {
			return UNKNOWN;
		}

		String normalized = input.trim().toUpperCase();

		// Exact matches
		for (CloudProvider provider : values()) {
			if (provider.name().equals(normalized)) {
				return provider;
			}
		}

		// Partial matches
		if (normalized.contains("GOOGLE") || normalized.contains("GCP")) {
			return GCP;
		} else if (normalized.contains("AMAZON") || normalized.contains("AWS")) {
			return AWS;
		} else if (normalized.contains("MICROSOFT") || normalized.contains("AZURE")) {
			return AZURE;
		} else if (normalized.contains("ORACLE") || normalized.contains("OCI")) {
			return OCI;
		} else if (normalized.contains("DATABRICKS")) {
			return DATABRICKS;
		} else if (normalized.contains("UNITY") || normalized.equals("UC")) {
			return UNITY;
		}

		return UNKNOWN;
	}

	/**
	 * Returns the full display name of the cloud provider.
	 * 
	 * @return The display name of the cloud provider
	 */
	public String getDisplayName() {
		return switch (this) {
			case GCP -> "Google Cloud Platform";
			case AWS -> "Amazon Web Services";
			case AZURE -> "Microsoft Azure";
			case OCI -> "Oracle Cloud Infrastructure";
			case DATABRICKS -> "Databricks";
			case UNITY -> "Unity Catalog";
			case UNKNOWN -> "Unknown";
		};
	}
}