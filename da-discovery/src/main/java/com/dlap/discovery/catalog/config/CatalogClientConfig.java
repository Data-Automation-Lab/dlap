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

package com.dlap.discovery.catalog.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.dlap.discovery.catalog.client.CatalogClient;
import com.dlap.discovery.catalog.client.impl.DefaultCatalogClient;
import com.dlap.discovery.common.config.Jackson2ClientConverters;

/**
 * Configuration for the catalog client.
 */
@Configuration
@EnableConfigurationProperties(CatalogClientConfig.CatalogProperties.class)
public class CatalogClientConfig {

	/**
	 * Properties for the external catalog configuration.
	 */
	@ConfigurationProperties(prefix = "catalog.external")
	public static class CatalogProperties {
		private boolean enabled = false;
		private String baseUrl = "http://localhost:8080/api/catalog";
		private String apiKey;
		private int connectionTimeout = 5000;
		private int readTimeout = 5000;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public int getConnectionTimeout() {
			return connectionTimeout;
		}

		public void setConnectionTimeout(int connectionTimeout) {
			this.connectionTimeout = connectionTimeout;
		}

		public int getReadTimeout() {
			return readTimeout;
		}

		public void setReadTimeout(int readTimeout) {
			this.readTimeout = readTimeout;
		}
	}

	@Bean
	@ConditionalOnProperty(name = "catalog.external.enabled", havingValue = "true")
	public CatalogClient catalogClient(CatalogProperties properties) {
		// Jackson 2 for JSON, as before Spring Framework 7 (BOOT-01).
		RestTemplate restTemplate = Jackson2ClientConverters.restTemplate();
		return new DefaultCatalogClient(restTemplate, properties.getBaseUrl(), properties.getApiKey());
	}
}