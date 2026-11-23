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

package com.dlap.discovery.event.service;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Service responsible for recording real-time metrics using Micrometer.
 * This service provides methods to record various types of metrics, such as
 * resource-specific metrics and event processing metrics.
 */
@Service
public class MetricsService {

	private final MeterRegistry meterRegistry;

	/**
	 * Constructs a new MetricsService.
	 *
	 * @param meterRegistry The Micrometer registry to use for recording metrics.
	 */
	public MetricsService(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Records a gauge metric for a specific resource.
	 *
	 * @param resourceId The unique identifier of the resource.
	 * @param metricName The name of the metric (e.g., "cpuUsage", "memoryUsage").
	 * @param value      The current value of the metric.
	 * @param tags       Additional tags to associate with the metric (e.g.,
	 *                   provider, region).
	 */
	public void recordResourceMetric(String resourceId, String metricName, double value, Tags tags) {
		// Consider adding a prefix for better organization, e.g., "discovery.resource."
		meterRegistry.gauge(
				"discovery.resource." + metricName,
				tags.and(Tag.of("resourceId", resourceId)),
				value);
	}

	/**
	 * Records a counter metric for processed events.
	 * Increments a counter based on the event type, provider, and change type.
	 *
	 * @param eventType  The type of the event (e.g., "ResourceEvent", "LogEvent").
	 * @param provider   The cloud provider associated with the event.
	 * @param changeType The type of change indicated by the event (e.g., "CREATE",
	 *                   "UPDATE", "DELETE").
	 */
	public void recordEventMetric(String eventType, String provider, String changeType) {
		meterRegistry.counter(
				"discovery.events",
				Tags.of(
						Tag.of("eventType", eventType != null ? eventType : "unknown"),
						Tag.of("provider", provider != null ? provider : "unknown"),
						Tag.of("changeType", changeType != null ? changeType : "unknown")))
				.increment();
	}

	/**
	 * Records an exception event metric.
	 * Increments a counter for exceptions, tagged by exception type and potentially
	 * severity or origin.
	 *
	 * @param exception The exception that occurred.
	 * @param tags      Additional tags (e.g., severity, origin component).
	 */
	public void recordExceptionMetric(Exception exception, Tags tags) {
		String exceptionType = exception != null ? exception.getClass().getSimpleName() : "unknown";
		meterRegistry.counter(
				"discovery.exceptions",
				tags.and(Tag.of("exceptionType", exceptionType))).increment();
	}

}