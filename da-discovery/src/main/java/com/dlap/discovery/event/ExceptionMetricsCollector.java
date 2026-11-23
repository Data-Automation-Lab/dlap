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

package com.dlap.discovery.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.log.service.Severity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Component that collects metrics about exceptions thrown in the application.
 * Records metrics for different exception types, error codes, and severity
 * levels.
 */
@Component
public class ExceptionMetricsCollector {

	private static final Logger log = LoggerFactory.getLogger(ExceptionMetricsCollector.class);

	private final MeterRegistry meterRegistry;

	// Track counts for periodic logging
	private final Map<String, AtomicLong> errorCodeCounts = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> severityCounts = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> exceptionTypeCounts = new ConcurrentHashMap<>();

	// Total count of all exceptions
	private final AtomicLong totalExceptionCount = new AtomicLong(0);

	/**
	 * Creates a new ExceptionMetricsCollector.
	 * 
	 * @param meterRegistry The meter registry for recording metrics
	 */
	public ExceptionMetricsCollector(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		initializeMetrics();
	}

	/**
	 * Initialize any global metrics.
	 */
	private void initializeMetrics() {
		// Initialize a gauge for the total number of exceptions
		meterRegistry.gauge("discovery.exceptions.total", totalExceptionCount);
	}

	/**
	 * Records metrics for a thrown DiscoveryException.
	 * 
	 * @param exception The exception to record metrics for
	 */
	public void recordException(DiscoveryException exception) {
		// Increment total count
		totalExceptionCount.incrementAndGet();

		// Extract metadata
		ErrorCode errorCode = exception.getErrorCode();
		Severity severity = errorCode.getSeverity();
		String exceptionType = exception.getClass().getSimpleName();

		// Record metrics in Micrometer
		Counter.builder("discovery.exceptions")
				.tags(Tags.of(
						Tag.of("errorCode", String.valueOf(errorCode.getCode())),
						Tag.of("errorName", errorCode.name()),
						Tag.of("severity", severity.name()),
						Tag.of("exceptionType", exceptionType)))
				.register(meterRegistry)
				.increment();

		// Update local counts for periodic logging
		errorCodeCounts.computeIfAbsent(errorCode.name(), k -> new AtomicLong(0)).incrementAndGet();
		severityCounts.computeIfAbsent(severity.name(), k -> new AtomicLong(0)).incrementAndGet();
		exceptionTypeCounts.computeIfAbsent(exceptionType, k -> new AtomicLong(0)).incrementAndGet();
	}

	/**
	 * Records metrics for a generic exception.
	 * 
	 * @param exception The exception to record metrics for
	 */
	public void recordException(Exception exception) {
		// Increment total count
		totalExceptionCount.incrementAndGet();

		String exceptionType = exception.getClass().getSimpleName();

		// Record metrics in Micrometer
		Counter.builder("discovery.exceptions.generic")
				.tags(Tags.of(
						Tag.of("exceptionType", exceptionType)))
				.register(meterRegistry)
				.increment();

		// Update local counts for periodic logging
		exceptionTypeCounts.computeIfAbsent(exceptionType, k -> new AtomicLong(0)).incrementAndGet();
	}

	/**
	 * Logs summary of exceptions periodically.
	 * Runs every 15 minutes.
	 */
	@Scheduled(fixedRate = 900000) // 15 minutes
	public void logExceptionSummary() {
		long total = totalExceptionCount.get();
		if (total > 0) {
			log.info("Exception summary - Total exceptions: {}", total);

			// Log error code counts
			StringBuilder errorCodeSummary = new StringBuilder("Error codes: ");
			errorCodeCounts.forEach(
					(code, count) -> errorCodeSummary.append(code).append("=").append(count.get()).append(", "));
			log.info(errorCodeSummary.toString());

			// Log severity counts
			StringBuilder severitySummary = new StringBuilder("Severities: ");
			severityCounts.forEach(
					(severity, count) -> severitySummary.append(severity).append("=").append(count.get()).append(", "));
			log.info(severitySummary.toString());

			// Log exception type counts
			StringBuilder typeSummary = new StringBuilder("Exception types: ");
			exceptionTypeCounts
					.forEach((type, count) -> typeSummary.append(type).append("=").append(count.get()).append(", "));
			log.info(typeSummary.toString());
		}
	}
}