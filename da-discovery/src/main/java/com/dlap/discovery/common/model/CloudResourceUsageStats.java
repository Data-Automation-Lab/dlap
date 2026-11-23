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
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Holds usage statistics for a specific CloudResource over a defined time
 * window, as well as cumulative counts.
 * This entity represents aggregated usage metrics for a CloudResource.
 */
@Entity
@Table(name = "cloud_resource_usage_stats")
public class CloudResourceUsageStats {

	/**
	 * Unique identifier for this usage statistics record.
	 */
	@Id
	@GeneratedValue(generator = "UUID")
	@GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;

	/**
	 * The CloudResource these statistics pertain to.
	 */
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "resource_db_id", nullable = false, unique = true) // unique = true for OneToOne
	private CloudResource cloudResource; // Changed from resourceId

	/**
	 * Count of read operations within the current time window.
	 */
	@Column(name = "read_count")
	private long readCount;

	/**
	 * Count of write operations within the current time window.
	 */
	@Column(name = "write_count")
	private long writeCount;

	/**
	 * Count of edit/update operations within the current time window.
	 */
	@Column(name = "edit_count")
	private long editCount;

	/**
	 * Total cumulative count of read operations since tracking began.
	 */
	@Column(name = "total_read_count")
	private long totalReadCount;

	/**
	 * Total cumulative count of write operations since tracking began.
	 */
	@Column(name = "total_write_count")
	private long totalWriteCount;

	/**
	 * Total cumulative count of edit/update operations since tracking began.
	 */
	@Column(name = "total_edit_count")
	private long totalEditCount;

	/**
	 * Timestamp of the last recorded access (read, write, or edit) within the time
	 * window.
	 */
	@Column(name = "last_accessed_at")
	private Instant lastAccessedAt;

	/**
	 * Start time of the aggregation window for these statistics.
	 */
	@Column(name = "time_window_start", nullable = false)
	private Instant timeWindowStart;

	/**
	 * End time of the aggregation window for these statistics.
	 */
	@Column(name = "time_window_end", nullable = false)
	private Instant timeWindowEnd;

	// Default constructor for JPA
	protected CloudResourceUsageStats() {
	}

	/**
	 * Constructs a new CloudResourceUsageStats record for a specific resource and
	 * time window.
	 *
	 * @param cloudResource   The CloudResource these stats belong to.
	 * @param timeWindowStart Start of the aggregation window.
	 * @param timeWindowEnd   End of the aggregation window.
	 */
	public CloudResourceUsageStats(CloudResource cloudResource, Instant timeWindowStart, Instant timeWindowEnd) {
		this.cloudResource = Objects.requireNonNull(cloudResource, "CloudResource cannot be null");
		this.timeWindowStart = Objects.requireNonNull(timeWindowStart, "TimeWindowStart cannot be null");
		this.timeWindowEnd = Objects.requireNonNull(timeWindowEnd, "TimeWindowEnd cannot be null");
		// Initialize counts
		this.readCount = 0;
		this.writeCount = 0;
		this.editCount = 0;
		this.totalReadCount = 0;
		this.totalWriteCount = 0;
		this.totalEditCount = 0;
	}

	// --- Getters and Setters ---

	public UUID getId() {
		return id;
	}

	// No setId for generated primary key

	public CloudResource getCloudResource() {
		return cloudResource;
	}

	public void setCloudResource(CloudResource cloudResource) {
		this.cloudResource = cloudResource;
	}

	public long getReadCount() {
		return readCount;
	}

	public void setReadCount(long readCount) {
		this.readCount = readCount;
	}

	// Convenience method to increment read count (both windowed and total)
	public void incrementReadCount() {
		this.readCount++;
		this.totalReadCount++;
	}

	public long getWriteCount() {
		return writeCount;
	}

	public void setWriteCount(long writeCount) {
		this.writeCount = writeCount;
	}

	// Convenience method to increment write count (both windowed and total)
	public void incrementWriteCount() {
		this.writeCount++;
		this.totalWriteCount++;
	}

	public long getEditCount() {
		return editCount;
	}

	public void setEditCount(long editCount) {
		this.editCount = editCount;
	}

	// Convenience method to increment edit count (both windowed and total)
	public void incrementEditCount() {
		this.editCount++;
		this.totalEditCount++;
	}

	public Instant getLastAccessedAt() {
		return lastAccessedAt;
	}

	public void setLastAccessedAt(Instant lastAccessedAt) {
		this.lastAccessedAt = lastAccessedAt;
	}

	public Instant getTimeWindowStart() {
		return timeWindowStart;
	}

	public void setTimeWindowStart(Instant timeWindowStart) {
		this.timeWindowStart = timeWindowStart;
	}

	public Instant getTimeWindowEnd() {
		return timeWindowEnd;
	}

	public void setTimeWindowEnd(Instant timeWindowEnd) {
		this.timeWindowEnd = timeWindowEnd;
	}

	// --- New Getters/Setters for Total Counts ---

	public long getTotalReadCount() {
		return totalReadCount;
	}

	public void setTotalReadCount(long totalReadCount) {
		this.totalReadCount = totalReadCount;
	}

	public long getTotalWriteCount() {
		return totalWriteCount;
	}

	public void setTotalWriteCount(long totalWriteCount) {
		this.totalWriteCount = totalWriteCount;
	}

	public long getTotalEditCount() {
		return totalEditCount;
	}

	public void setTotalEditCount(long totalEditCount) {
		this.totalEditCount = totalEditCount;
	}

	// --- equals, hashCode, toString ---

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		// Use instanceof check for proxy safety with JPA
		if (!(o instanceof CloudResourceUsageStats that))
			return false;
		// Rely on ID for equality if persisted, otherwise object identity
		return id != null && Objects.equals(id, that.id);
	}

	@Override
	public int hashCode() {
		// Use a constant for entities without an ID yet, or ID's hashcode
		return id != null ? Objects.hash(id) : System.identityHashCode(this);
	}

	@Override
	public String toString() {
		return "CloudResourceUsageStats{" +
				"id=" + id +
				", cloudResourceId=" + (cloudResource != null ? cloudResource.getId() : "null") + // Avoid infinite loop
				", readCount=" + readCount +
				", writeCount=" + writeCount +
				", editCount=" + editCount +
				", totalReadCount=" + totalReadCount +
				", totalWriteCount=" + totalWriteCount +
				", totalEditCount=" + totalEditCount +
				", lastAccessedAt=" + lastAccessedAt +
				", timeWindowStart=" + timeWindowStart +
				", timeWindowEnd=" + timeWindowEnd +
				'}';
	}
}