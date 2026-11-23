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

package com.dlap.discovery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * Configuration for enabling or disabling cloud provider-specific beans
 * based on application configuration.
 */
@Configuration
public class CloudProvidersConfig {

	private static final Logger log = LoggerFactory.getLogger(CloudProvidersConfig.class);

	/**
	 * GCP configuration section - all beans are created only if
	 * cloud.provider.gcp.enabled=true
	 */
	@Configuration
	@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
	public static class GcpConfiguration {

		/**
		 * R-P0-2 (finding N6): the previous implementation caught any exception
		 * during {@link BigQueryOptions#getDefaultInstance()} and returned a
		 * synthetic client wired to project id {@code "mock-project-id"}. That
		 * "fail-soft" behaviour silently produced a non-functional client whose
		 * every API call would 404 at runtime, masking misconfiguration as a
		 * working deployment. Removed: the bean now propagates initialization
		 * failures so the deployment fails fast at boot if GCP credentials or
		 * project id are not configured. Operators set
		 * {@code GOOGLE_APPLICATION_CREDENTIALS} or run the service on a host
		 * with default GCP credentials (GKE workload identity, gcloud login,
		 * etc.).
		 */
		@Bean
		public BigQuery bigQueryClient() {
			log.info("Initializing GCP BigQuery client");
			return BigQueryOptions.getDefaultInstance().getService();
		}

		/**
		 * R-P0-2 (finding N6): same fix as {@link #bigQueryClient()} - drop the
		 * silent {@code "mock-project-id"} fallback. Misconfigured GCP
		 * credentials now produce a clean boot-time failure instead of a
		 * runtime 404 storm.
		 */
		@Bean
		public Storage storageClient() {
			log.info("Initializing GCP Storage client");
			return StorageOptions.getDefaultInstance().getService();
		}
	}

	/**
	 * AWS configuration section - all beans are created only if
	 * cloud.provider.aws.enabled=true
	 */
	@Configuration
	@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
	public static class AwsConfiguration {
		// AWS specific beans would go here
	}

	/**
	 * Azure configuration section - all beans are created only if
	 * cloud.provider.azure.enabled=true
	 */
	@Configuration
	@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
	public static class OciConfiguration {
		// OCI specific beans would go here
	}
}