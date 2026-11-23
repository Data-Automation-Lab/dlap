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

package com.dlap.discovery.sd.config;

import java.util.Collections;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.azure.config.AzureConfigService;
import com.dlap.discovery.common.config.cloud.impl.oracle.OracleConfigService;
import com.dlap.discovery.common.model.ResourceType;

/**
 * Test configuration that provides mock implementations for various services.
 */
@TestConfiguration
public class TestConfig {

	/**
	 * Provides a mock AWSConfigService for tests if none is present.
	 */
	@Bean
	@ConditionalOnMissingBean
	@Primary
	public AWSConfigService mockAwsConfigService() {
		return new AWSConfigService() {
			@Override
			public String getAccessKey() {
				return "test-access-key";
			}

			@Override
			public String getSecretKey() {
				return "test-secret-key";
			}

			@Override
			public String getRegion() {
				return "us-west-2";
			}

			@Override
			public boolean isSsmEnabled() {
				return false;
			}

			@Override
			public String getSsmPrefix() {
				return "/test-prefix/";
			}

			@Override
			public String getS3BucketName() {
				return "test-bucket";
			}

			@Override
			public String getDynamoDBTableName() {
				return "test-table";
			}

			@Override
			public Map<String, String> getTags(ResourceType resourceType) {
				return Collections.emptyMap();
			}

			@Override
			public String getAccountId() {
				return "test-account-id";
			}
		};
	}

	/**
	 * Provides a mock AzureConfigService for tests if none is present.
	 */
	@Bean
	@ConditionalOnMissingBean
	@Primary
	public AzureConfigService mockAzureConfigService() {
		return new AzureConfigService() {
			@Override
			public String getClientId() {
				return "test-client-id";
			}

			@Override
			public String getClientSecret() {
				return "test-client-secret";
			}

			@Override
			public String getTenantId() {
				return "test-tenant-id";
			}

			// If AzureConfigService has other abstract methods, implement them here
		};
	}

	/**
	 * Provides a mock OracleConfigService for tests if none is present.
	 */
	@Bean
	@ConditionalOnMissingBean
	@Primary
	public OracleConfigService mockOracleConfigService() {
		return new OracleConfigService() {
			@Override
			public String getConfigFilePath() {
				return "classpath:config/test-oci-config";
			}

			@Override
			public String getProfileName() {
				return "DEFAULT";
			}

			@Override
			public String getTenancyId() {
				return "test-tenancy-id";
			}

			@Override
			public String getRegion() {
				return "us-phoenix-1";
			}

			@Override
			public String getDefaultCompartment() {
				return "test-compartment";
			}

			@Override
			public String getCompartmentId() {
				return "test-compartment-id";
			}

			// If OracleConfigService has other abstract methods, implement them here
		};
	}
}