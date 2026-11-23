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

package com.dlap.discovery.job.integration.nifi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.dlap.discovery.job.executable.NiFiTaskConfiguration;
import com.dlap.discovery.common.config.Jackson2ClientConverters;

/**
 * Client for interacting with Apache NiFi REST API.
 * Handles job submission, monitoring, and management.
 */
@Component
public class NiFiClient {
	private static final Logger log = LoggerFactory.getLogger(NiFiClient.class);

	private final RestTemplate restTemplate;
	private final String nifiApiUrl;
	private final String apiKey;

	public NiFiClient(
			@Value("${nifi.api.url:http://localhost:8080/nifi-api}") String nifiApiUrl,
			@Value("${nifi.api.key:}") String apiKey) {
		this.nifiApiUrl = nifiApiUrl;
		this.apiKey = apiKey;
		// Jackson 2 for JSON, as before Spring Framework 7 (BOOT-01).
		this.restTemplate = Jackson2ClientConverters.restTemplate();
	}

	/**
	 * Submits a task to NiFi for execution.
	 * 
	 * @param config Task configuration
	 * @return processor ID assigned to the task
	 */
	public String submitTask(NiFiTaskConfiguration config) {
		log.info("Submitting task to NiFi: {}", config.getProcessGroupId());

		HttpHeaders headers = createHeaders();

		// Create the request payload (simplified for example)
		String payload = createPayload(config);

		HttpEntity<String> entity = new HttpEntity<>(payload, headers);

		String url = nifiApiUrl + "/process-groups/" + config.getProcessGroupId() + "/processors";

		ResponseEntity<String> response = restTemplate.exchange(
				url, HttpMethod.POST, entity, String.class);

		// Parse response to extract processor ID (simplified for example)
		String processorId = parseProcessorId(response.getBody());

		// Start the processor
		startProcessor(processorId);

		return processorId;
	}

	/**
	 * Stops a processor.
	 * 
	 * @param processorId Processor ID
	 * @return true if successful
	 */
	public boolean stopProcessor(String processorId) {
		log.info("Stopping NiFi processor: {}", processorId);

		try {
			HttpHeaders headers = createHeaders();
			HttpEntity<String> entity = new HttpEntity<>(
					"{\"state\":\"STOPPED\",\"revision\":{\"version\":0}}", headers);

			String url = nifiApiUrl + "/processors/" + processorId;

			restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
			return true;
		} catch (Exception e) {
			log.error("Failed to stop processor: {}", processorId, e);
			return false;
		}
	}

	/**
	 * Pauses a processor.
	 * 
	 * @param processorId Processor ID
	 * @return true if successful
	 */
	public boolean pauseProcessor(String processorId) {
		// In NiFi, pausing is effectively the same as stopping
		return stopProcessor(processorId);
	}

	/**
	 * Starts a processor.
	 * 
	 * @param processorId Processor ID
	 * @return true if successful
	 */
	public boolean startProcessor(String processorId) {
		log.info("Starting NiFi processor: {}", processorId);

		try {
			HttpHeaders headers = createHeaders();
			HttpEntity<String> entity = new HttpEntity<>(
					"{\"state\":\"RUNNING\",\"revision\":{\"version\":0}}", headers);

			String url = nifiApiUrl + "/processors/" + processorId;

			restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
			return true;
		} catch (Exception e) {
			log.error("Failed to start processor: {}", processorId, e);
			return false;
		}
	}

	/**
	 * Gets the status of a processor.
	 * 
	 * @param processorId Processor ID
	 * @return status string
	 */
	public String getProcessorStatus(String processorId) {
		log.info("Getting status for NiFi processor: {}", processorId);

		try {
			HttpHeaders headers = createHeaders();
			HttpEntity<String> entity = new HttpEntity<>(headers);

			String url = nifiApiUrl + "/processors/" + processorId;

			ResponseEntity<String> response = restTemplate.exchange(
					url, HttpMethod.GET, entity, String.class);

			// Parse response to extract status (simplified for example)
			return parseProcessorStatus(response.getBody());
		} catch (Exception e) {
			log.error("Failed to get processor status: {}", processorId, e);
			return null;
		}
	}

	private HttpHeaders createHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		if (apiKey != null && !apiKey.isEmpty()) {
			headers.set("Authorization", "Bearer " + apiKey);
		}

		return headers;
	}

	private String createPayload(NiFiTaskConfiguration config) {
		// Simplified example - in a real implementation, this would create a
		// proper JSON payload based on the NiFi REST API requirements
		return "{" +
				"\"component\": {" +
				"\"type\": \"" + config.getProcessorType() + "\"," +
				"\"name\": \"" + config.getJobName() + "\"," +
				"\"properties\": " + config.getPropertiesJson() +
				"}," +
				"\"revision\": {\"version\": 0}" +
				"}";
	}

	private String parseProcessorId(String responseBody) {
		// Simplified example - in a real implementation, this would properly
		// parse the JSON response to extract the processor ID
		// This is a placeholder implementation
		return responseBody.contains("\"id\":\"")
				? responseBody.split("\"id\":\"")[1].split("\"")[0]
				: "unknown-processor-id";
	}

	private String parseProcessorStatus(String responseBody) {
		// Simplified example - in a real implementation, this would properly
		// parse the JSON response to extract the processor status
		// This is a placeholder implementation
		return responseBody.contains("\"state\":\"")
				? responseBody.split("\"state\":\"")[1].split("\"")[0]
				: "UNKNOWN";
	}
}