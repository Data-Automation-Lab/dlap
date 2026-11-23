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

package com.dlap.discovery.common.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

/**
 * Represents a persisted metric record.
 * This entity stores individual metric data points along with relevant metadata
 * such as timestamp, tags, and time window information for aggregation.
 */
@Entity
@Table(name = "metrics")
public class MetricEntity {

	/**
	 * Unique identifier for the metric record.
	 */
	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	/**
	 * The name of the metric (e.g., "resource.count", "event.processed.time").
	 */
	@Column(name = "metric_name", nullable = false)
	private String metricName;

	/**
	 * The numerical value of the metric.
	 */
	@Column(name = "metric_value", nullable = false)
	private double value;

	/**
	 * The timestamp when the metric was recorded.
	 */
	@Column(name = "timestamp", nullable = false)
	private Instant timestamp;

	/**
	 * Key-value pairs providing context for the metric (e.g., provider, region,
	 * resource_type).
	 * Stored as JSONB for flexibility and efficient querying.
	 */
	@ElementCollection(fetch = FetchType.EAGER) // Eager fetch might be okay for tags
	@CollectionTable(name = "metric_tags", joinColumns = @JoinColumn(name = "metric_id"))
	@MapKeyColumn(name = "tag_key")
	@Column(name = "tag_value")
	@JdbcTypeCode(SqlTypes.JSON) // Use JSONB for PostgreSQL compatibility
	private Map<String, String> tags;

	/**
	 * The start time of the aggregation window this metric belongs to (optional).
	 * Useful for pre-aggregated or time-windowed metrics.
	 */
	@Column(name = "window_start")
	private Instant windowStart;

	/**
	 * The end time of the aggregation window this metric belongs to (optional).
	 */
	@Column(name = "window_end")
	private Instant windowEnd;

	// --- Constructors ---

	/**
	 * Default constructor for JPA.
	 */
	protected MetricEntity() {
	}

	/**
	 * Creates a new MetricEntity.
	 *
	 * @param id         Unique ID.
	 * @param metricName Name of the metric.
	 * @param value      Value of the metric.
	 * @param timestamp  Timestamp of recording.
	 * @param tags       Contextual tags.
	 */
	public MetricEntity(UUID id, String metricName, double value, Instant timestamp, Map<String, String> tags) {
		this.id = id;
		this.metricName = metricName;
		this.value = value;
		this.timestamp = timestamp;
		this.tags = tags;
	}

	// --- Getters and Setters ---

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getMetricName() {
		return metricName;
	}

	public void setMetricName(String metricName) {
		this.metricName = metricName;
	}

	public double getValue() {
		return value;
	}

	public void setValue(double value) {
		this.value = value;
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public void setTags(Map<String, String> tags) {
		this.tags = tags;
	}

	public Instant getWindowStart() {
		return windowStart;
	}

	public void setWindowStart(Instant windowStart) {
		this.windowStart = windowStart;
	}

	public Instant getWindowEnd() {
		return windowEnd;
	}

	public void setWindowEnd(Instant windowEnd) {
		this.windowEnd = windowEnd;
	}

	// --- equals, hashCode, toString ---

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		MetricEntity that = (MetricEntity) o;
		return id.equals(that.id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public String toString() {
		return "MetricEntity{" +
				"id=" + id +
				", metricName='" + metricName + '\'' +
				", value=" + value +
				", timestamp=" + timestamp +
				'}';
	}
}