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

package com.dlap.discovery.crawler.exception;

import java.util.HashMap;
import java.util.Map;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.common.service.ResourceNotFoundException;
import com.dlap.discovery.crawler.service.CrawlerException;
import com.dlap.discovery.log.exception.CloudProviderException;

/**
 * Utility class with factory methods for creating exceptions with consistent
 * patterns.
 * This centralizes exception creation logic and provides a convenient API for
 * developers.
 */
public final class ExceptionUtils {

	private ExceptionUtils() {
		// Utility class - prevent instantiation
	}

	// Job exceptions

	/**
	 * Creates a DiscoveryException for a job not found scenario.
	 * 
	 * @param jobId The ID of the job that wasn't found
	 * @return A DiscoveryException with JOB_NOT_FOUND code
	 */
	public static DiscoveryException jobNotFound(String jobId) {
		Map<String, Object> details = new HashMap<>();
		details.put("jobId", jobId);

		return new DiscoveryException(
				ErrorCode.JOB_NOT_FOUND,
				String.format("Job not found: %s", jobId),
				details);
	}

	// Resource exceptions

	/**
	 * Creates a ResourceNotFoundException for the specified resource.
	 * 
	 * @param resourceType The type of resource (e.g., "CloudResource", "Bucket")
	 * @param resourceId   The ID of the resource
	 * @return A ResourceNotFoundException
	 */
	public static ResourceNotFoundException resourceNotFound(String resourceType, String resourceId) {
		return new ResourceNotFoundException(resourceType, resourceId);
	}

	/**
	 * Creates a ResourceNotFoundException with a custom message.
	 * 
	 * @param message A custom error message
	 * @return A ResourceNotFoundException
	 */
	public static ResourceNotFoundException resourceNotFound(String message) {
		return new ResourceNotFoundException(message);
	}

	/**
	 * Creates a DiscoveryException for a resource type not found scenario.
	 * 
	 * @param resourceTypeId The ID of the resource type that wasn't found
	 * @return A DiscoveryException with RESOURCE_TYPE_NOT_FOUND code
	 */
	public static DiscoveryException resourceTypeNotFound(String resourceTypeId) {
		Map<String, Object> details = new HashMap<>();
		details.put("resourceTypeId", resourceTypeId);

		return new DiscoveryException(
				ErrorCode.RESOURCE_TYPE_NOT_FOUND,
				String.format("Resource type not found: %s", resourceTypeId),
				details);
	}

	// Cloud provider exceptions

	/**
	 * Creates a CloudProviderException for a connection error.
	 * 
	 * @param provider  The cloud provider name
	 * @param operation The operation that failed
	 * @param cause     The underlying exception
	 * @return A CloudProviderException
	 */
	public static CloudProviderException providerConnectionError(String provider, String operation, Throwable cause) {
		return new CloudProviderException(ErrorCode.PROVIDER_CONNECTION_ERROR, provider, operation, cause);
	}

	/**
	 * Creates a CloudProviderException for an API error.
	 * 
	 * @param provider     The cloud provider name
	 * @param operation    The operation that failed
	 * @param errorDetails Additional error details
	 * @return A CloudProviderException
	 */
	public static CloudProviderException providerApiError(String provider, String operation, String errorDetails) {
		return new CloudProviderException(ErrorCode.PROVIDER_API_ERROR, provider, operation, errorDetails);
	}

	/**
	 * Creates a CloudProviderException for a resource not found on the provider.
	 * 
	 * @param provider     The cloud provider name
	 * @param resourceType The type of resource
	 * @param resourceId   The ID of the resource
	 * @return A CloudProviderException
	 */
	public static CloudProviderException providerResourceNotFound(String provider, String resourceType,
			String resourceId) {
		String operation = String.format("get %s [%s]", resourceType, resourceId);
		String details = String.format("%s with ID %s not found on provider", resourceType, resourceId);

		return new CloudProviderException(ErrorCode.PROVIDER_RESOURCE_NOT_FOUND, provider, operation, details);
	}

	// Crawler exceptions

	/**
	 * Creates a CrawlerException for a crawler initialization failure.
	 * 
	 * @param crawlerName The name of the crawler
	 * @param cause       The underlying exception
	 * @return A CrawlerException
	 */
	public static CrawlerException crawlerInitializationFailed(String crawlerName, Throwable cause) {
		return new CrawlerException(ErrorCode.CRAWLER_INITIALIZATION_FAILED, crawlerName, null, "initialization",
				cause);
	}

	/**
	 * Creates a CrawlerException for a crawler execution failure.
	 * 
	 * @param crawlerName The name of the crawler
	 * @param jobId       The ID of the job that was executing
	 * @param operation   The operation that failed
	 * @param cause       The underlying exception
	 * @return A CrawlerException
	 */
	public static CrawlerException crawlerExecutionFailed(String crawlerName, String jobId, String operation,
			Throwable cause) {
		return new CrawlerException(ErrorCode.CRAWLER_EXECUTION_FAILED, crawlerName, jobId, operation, cause);
	}

	/**
	 * Creates a CrawlerException for a crawler dependency that's missing.
	 * 
	 * @param crawlerName    The name of the crawler
	 * @param dependencyName The name of the missing dependency
	 * @return A CrawlerException
	 */
	public static CrawlerException crawlerDependencyMissing(String crawlerName, String dependencyName) {
		Map<String, Object> details = new HashMap<>();
		details.put("crawlerName", crawlerName);
		details.put("dependencyName", dependencyName);

		return new CrawlerException(
				ErrorCode.CRAWLER_DEPENDENCY_MISSING,
				String.format("Required dependency '%s' missing for crawler: %s", dependencyName, crawlerName));
	}

	// Configuration exceptions

	/**
	 * Creates a DiscoveryException for a missing configuration item.
	 * 
	 * @param configName The name of the missing configuration item
	 * @return A DiscoveryException with MISSING_CONFIGURATION code
	 */
	public static DiscoveryException missingConfiguration(String configName) {
		Map<String, Object> details = new HashMap<>();
		details.put("configName", configName);

		return new DiscoveryException(
				ErrorCode.MISSING_CONFIGURATION,
				String.format("Required configuration is missing: %s", configName),
				details);
	}

	/**
	 * Creates a DiscoveryException for an invalid configuration value.
	 * 
	 * @param configName The name of the configuration item
	 * @param value      The invalid value
	 * @param reason     The reason it's invalid
	 * @return A DiscoveryException with INVALID_CONFIGURATION code
	 */
	public static DiscoveryException invalidConfiguration(String configName, String value, String reason) {
		Map<String, Object> details = new HashMap<>();
		details.put("configName", configName);
		details.put("value", value);
		details.put("reason", reason);

		return new DiscoveryException(
				ErrorCode.INVALID_CONFIGURATION,
				String.format("Invalid configuration value for '%s': %s - %s", configName, value, reason),
				details);
	}

	// System exceptions

	/**
	 * Creates a DiscoveryException for an unexpected error.
	 * 
	 * @param message A description of the error
	 * @param cause   The underlying exception
	 * @return A DiscoveryException with UNEXPECTED_ERROR code
	 */
	public static DiscoveryException unexpectedError(String message, Throwable cause) {
		return new DiscoveryException(ErrorCode.UNEXPECTED_ERROR, message, cause);
	}

	/**
	 * Creates a DiscoveryException for an external service error.
	 * 
	 * @param serviceName The name of the external service
	 * @param operation   The operation that failed
	 * @param cause       The underlying exception
	 * @return A DiscoveryException with EXTERNAL_SERVICE_ERROR code
	 */
	public static DiscoveryException externalServiceError(String serviceName, String operation, Throwable cause) {
		Map<String, Object> details = new HashMap<>();
		details.put("serviceName", serviceName);
		details.put("operation", operation);

		return new DiscoveryException(
				ErrorCode.EXTERNAL_SERVICE_ERROR,
				String.format("Error in external service '%s' during operation: %s - %s",
						serviceName, operation, cause.getMessage()),
				details,
				cause);
	}

	// Parameter validation exceptions

	/**
	 * Creates a DiscoveryException for a missing parameter.
	 * 
	 * @param paramName The name of the missing parameter
	 * @param message   Additional message describing the issue
	 * @return A DiscoveryException with MISSING_PARAMETER code
	 */
	public static DiscoveryException missingParameter(String paramName, String message) {
		Map<String, Object> details = new HashMap<>();
		details.put("paramName", paramName);

		return new DiscoveryException(
				ErrorCode.MISSING_PARAMETER,
				message != null ? message : String.format("Required parameter is missing: %s", paramName),
				details);
	}

	/**
	 * Creates a DiscoveryException for an invalid parameter value.
	 * 
	 * @param paramName The name of the parameter
	 * @param value     The invalid value
	 * @param reason    The reason it's invalid
	 * @return A DiscoveryException with INVALID_PARAMETER code
	 */
	public static DiscoveryException invalidParameter(String paramName, String value, String reason) {
		Map<String, Object> details = new HashMap<>();
		details.put("paramName", paramName);
		details.put("value", value);
		details.put("reason", reason);

		return new DiscoveryException(
				ErrorCode.INVALID_PARAMETER,
				String.format("Invalid parameter value for '%s': %s - %s", paramName, value, reason),
				details);
	}
}