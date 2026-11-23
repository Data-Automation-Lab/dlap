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

package com.dlap.discovery.job.integration.spark;

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

import com.dlap.discovery.job.executable.SparkJobConfiguration;
import com.dlap.discovery.common.config.Jackson2ClientConverters;

/**
 * Client for interacting with Apache Spark REST API.
 * Handles job submission, monitoring, and management.
 */
@Component
public class SparkClient {
	private static final Logger log = LoggerFactory.getLogger(SparkClient.class);

	private final RestTemplate restTemplate;
	private final String sparkApiUrl;
	private final String apiKey;

	public SparkClient(
			@Value("${spark.api.url:http://localhost:6066}") String sparkApiUrl,
			@Value("${spark.api.key:}") String apiKey) {
		this.sparkApiUrl = sparkApiUrl;
		this.apiKey = apiKey;
		// Jackson 2 for JSON, as before Spring Framework 7 (BOOT-01).
		this.restTemplate = Jackson2ClientConverters.restTemplate();
	}

	/**
	 * Submits a job to Spark for execution.
	 * 
	 * @param config Job configuration
	 * @return application ID assigned to the job
	 */
	public String submitJob(SparkJobConfiguration config) {
		log.info("Submitting job to Spark: {}", config.getMainClass());

		HttpHeaders headers = createHeaders();

		// Create the request payload (simplified for example)
		String payload = createPayload(config);

		HttpEntity<String> entity = new HttpEntity<>(payload, headers);

		String url = sparkApiUrl + "/v1/submissions/create";

		ResponseEntity<String> response = restTemplate.exchange(
				url, HttpMethod.POST, entity, String.class);

		// Parse response to extract application ID (simplified for example)
		String applicationId = parseApplicationId(response.getBody());

		log.info("Spark application submitted with ID: {}", applicationId);

		return applicationId;
	}

	/**
	 * Kills a Spark application.
	 * 
	 * @param applicationId Application ID
	 * @return true if successful
	 */
	public boolean killApplication(String applicationId) {
		log.info("Killing Spark application: {}", applicationId);

		try {
			HttpHeaders headers = createHeaders();
			HttpEntity<String> entity = new HttpEntity<>(
					"{\"action\":\"kill\"}", headers);

			String url = sparkApiUrl + "/v1/submissions/kill/" + applicationId;

			restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
			return true;
		} catch (Exception e) {
			log.error("Failed to kill Spark application: {}", applicationId, e);
			return false;
		}
	}

	/**
	 * Gets the status of a Spark application.
	 * 
	 * @param applicationId Application ID
	 * @return status string
	 */
	public String getApplicationStatus(String applicationId) {
		log.info("Getting status for Spark application: {}", applicationId);

		try {
			HttpHeaders headers = createHeaders();
			HttpEntity<String> entity = new HttpEntity<>(headers);

			String url = sparkApiUrl + "/v1/submissions/status/" + applicationId;

			ResponseEntity<String> response = restTemplate.exchange(
					url, HttpMethod.GET, entity, String.class);

			// Parse response to extract status (simplified for example)
			return parseApplicationStatus(response.getBody());
		} catch (Exception e) {
			log.error("Failed to get application status: {}", applicationId, e);
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

	private String createPayload(SparkJobConfiguration config) {
		// Simplified example - in a real implementation, this would create a
		// proper JSON payload based on the Spark REST API requirements
		return "{" +
				"\"appResource\": \"" + config.getAppJarPath() + "\"," +
				"\"sparkProperties\": {" +
				"\"spark.app.name\": \"" + config.getAppName() + "\"," +
				"\"spark.master\": \"" + config.getMaster() + "\"," +
				"\"spark.driver.memory\": \"" + config.getDriverMemory() + "\"" +
				"}," +
				"\"mainClass\": \"" + config.getMainClass() + "\"," +
				"\"arguments\": " + config.getArgumentsJson() +
				"}";
	}

	private String parseApplicationId(String responseBody) {
		// Simplified example - in a real implementation, this would properly
		// parse the JSON response to extract the application ID
		// This is a placeholder implementation
		return responseBody.contains("\"submissionId\":\"")
				? responseBody.split("\"submissionId\":\"")[1].split("\"")[0]
				: "unknown-application-id";
	}

	private String parseApplicationStatus(String responseBody) {
		// Simplified example - in a real implementation, this would properly
		// parse the JSON response to extract the application status
		// This is a placeholder implementation
		return responseBody.contains("\"driverState\":\"")
				? responseBody.split("\"driverState\":\"")[1].split("\"")[0]
				: "UNKNOWN";
	}
}