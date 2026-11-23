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

package com.dlap.discovery.client.rest;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.common.service.ResourceNotFoundException;
import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.crawler.exception.ExceptionUtils;
import com.dlap.discovery.log.exception.CloudProviderException;

/**
 * REST controller that demonstrates using the new exception handling system.
 * This is an example controller that intentionally throws different types of
 * exceptions to showcase how they're handled.
 *
 * <p>Gated behind the {@code dev} Spring profile per R-P1-3 (finding N9).
 * Must never be reachable in production; the endpoints throw on the happy
 * path by design and exist only as runnable documentation for the error
 * taxonomy in {@link com.dlap.discovery.crawler.exception.ErrorCode}.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/discovery-examples")
public class DiscoveryExamplesController {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryExamplesController.class);

	/**
	 * Example endpoint that demonstrates throwing ResourceNotFoundException.
	 *
	 * @param resourceId The resource ID to "find"
	 * @return Never returns successfully, always throws an exception
	 */
	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping("/resources/{resourceId}")
	public ResponseEntity<Map<String, Object>> getResource(@PathVariable String resourceId) {
		log.info("Attempting to get resource with ID: {}", resourceId);

		// Simulate a resource not found scenario
		throw new ResourceNotFoundException("CloudResource", resourceId);
	}

	/**
	 * Example endpoint that demonstrates various parameter validation.
	 *
	 * @param providerId The cloud provider ID
	 * @param region     The region
	 * @return A response entity with validation results
	 */
	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping("/validate")
	public ResponseEntity<Map<String, String>> validateParameters(
			@RequestParam(required = false) String providerId,
			@RequestParam(required = false) String region) {

		// Validate provider parameter
		if (providerId == null || providerId.isBlank()) {
			throw ExceptionUtils.missingParameter("providerId", "Provider ID is required");
		}

		// Only accept valid provider IDs
		if (!List.of("aws", "gcp", "azure").contains(providerId.toLowerCase())) {
			throw ExceptionUtils.invalidParameter("providerId", providerId,

					"Provider ID must be one of: aws, gcp, azure");
		}

		// Validate region parameter
		if (region == null || region.isBlank()) {
			throw ExceptionUtils.missingParameter("region", "Region is required");
		}

		// Only certain regions are valid for certain providers
		if (providerId.equalsIgnoreCase("aws") && !region.startsWith("us-") && !region.startsWith("eu-")) {
			throw ExceptionUtils.invalidParameter("region", region,
					"For AWS, region must start with 'us-' or 'eu-'");
		}

		// If all validations pass, return success
		return ResponseEntity.ok(Map.of(
				"providerId", providerId,
				"region", region,
				"status", "valid"));
	}

	/**
	 * Example endpoint that demonstrates cloud provider errors.
	 *
	 * @param providerId The cloud provider ID
	 * @param operation  The operation to simulate
	 * @return Never returns successfully, always throws an exception
	 */
	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping("/providers/{providerId}/operations/{operation}")
	public ResponseEntity<Object> simulateProviderOperation(
			@PathVariable String providerId,
			@PathVariable String operation) {

		// Simulate different provider error scenarios based on the operation
		switch (operation.toLowerCase()) {
			case "connect":
				throw new CloudProviderException(
						ErrorCode.PROVIDER_CONNECTION_ERROR,
						providerId.toUpperCase(),
						"connect",
						"Connection to provider timed out after 60 seconds");

			case "auth":
				throw new CloudProviderException(
						ErrorCode.INVALID_CREDENTIALS,
						providerId.toUpperCase(),
						"authenticate",
						"API key has expired");

			case "quota":
				throw new CloudProviderException(
						ErrorCode.PROVIDER_QUOTA_EXCEEDED,
						providerId.toUpperCase(),
						"listResources",
						"Rate limit of 100 requests per minute exceeded");

			default:
				throw ExceptionUtils.invalidParameter("operation", operation,
						"Supported operations for simulation are: connect, auth, quota");
		}
	}

	/**
	 * Example endpoint that demonstrates configuration errors.
	 *
	 * @param config The configuration object
	 * @return Never returns successfully, always throws an exception
	 */
	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@PostMapping("/config/validate")
	public ResponseEntity<String> validateConfig(@RequestBody Map<String, Object> config) {
		// Check for required configuration items
		if (!config.containsKey("apiEndpoint")) {
			throw ExceptionUtils.missingConfiguration("apiEndpoint");
		}

		if (!config.containsKey("timeout")) {
			throw ExceptionUtils.missingConfiguration("timeout");
		}

		// Validate configuration values
		Object timeoutObj = config.get("timeout");
		if (!(timeoutObj instanceof Number)) {
			throw ExceptionUtils.invalidConfiguration(
					"timeout",
					timeoutObj != null ? timeoutObj.toString() : "null",
					"Timeout must be a number");
		}

		int timeout = ((Number) timeoutObj).intValue();
		if (timeout < 0 || timeout > 3600) {
			throw ExceptionUtils.invalidConfiguration(
					"timeout",
					String.valueOf(timeout),
					"Timeout must be between 0 and 3600 seconds");
		}

		// This would never actually be reached in this example
		return new ResponseEntity<>("Configuration is valid", HttpStatus.OK);
	}

	/**
	 * Example endpoint that demonstrates system errors.
	 *
	 * @param errorType The type of error to simulate
	 * @return Never returns successfully, always throws an exception
	 */
	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping("/errors/{errorType}")
	public ResponseEntity<Object> simulateSystemError(@PathVariable String errorType) {
		switch (errorType.toLowerCase()) {
			case "unexpected":
				try {
					// Simulate a real unexpected error
					String s = null;
					s.length(); // This will throw NullPointerException
					return ResponseEntity.ok("This will never be reached");
				} catch (Exception e) {
					throw ExceptionUtils.unexpectedError("An unexpected error occurred in the system", e);
				}

			case "external":
				throw ExceptionUtils.externalServiceError(
						"DatabaseService",
						"executeQuery",
						new RuntimeException("Connection pool exhausted"));

			case "resource-exhausted":
				throw new DiscoveryException(
						ErrorCode.RESOURCE_EXHAUSTED,
						"System memory limit reached (95% usage)");

			default:
				throw ExceptionUtils.invalidParameter("errorType", errorType,
						"Supported error types for simulation are: unexpected, external, resource-exhausted");
		}
	}
}