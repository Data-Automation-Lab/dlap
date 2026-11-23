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

package com.dlap.discovery.sd.web.rest.errors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Test application configuration to disable problematic components for tests.
 */
@TestConfiguration
public class TestApplicationConfig {

	/**
	 * This configuration helps to disable Google Cloud SQL for testing.
	 */
	@TestConfiguration
	@ConditionalOnProperty(name = "spring.cloud.gcp.sql.enabled", havingValue = "false", matchIfMissing = false)
	public static class GcpSqlDisabledConfig {
		// This class exists just to ensure GCP SQL is disabled for tests
	}

	/**
	 * This configuration helps to disable Google Cloud core for testing.
	 */
	@TestConfiguration
	@ConditionalOnProperty(name = "spring.cloud.gcp.core.enabled", havingValue = "false", matchIfMissing = false)
	public static class GcpCoreDisabledConfig {
		// This class exists just to ensure GCP core is disabled for tests
	}
}