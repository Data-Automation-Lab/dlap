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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Represents the lineage information for a CloudResource, tracking its origin
 * and transformations.
 * This entity tracks historical lineage events for a resource.
 */
@Entity
@Table(name = "lineage")
public class Lineage {

	/**
	 * Unique identifier for this specific lineage record.
	 */
	@Id
	@GeneratedValue(generator = "UUID")
	@GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;

	/**
	 * The CloudResource this lineage record pertains to.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "resource_db_id", nullable = false)
	private CloudResource cloudResource; // Changed from resourceId

	/**
	 * IDs of resources used as input/source for the action described in this
	 * lineage record.
	 * Storing as simple UUID list for now.
	 * Consider a ManyToMany relationship if source resources are also managed
	 * entities.
	 */
	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "lineage_source_resources", joinColumns = @JoinColumn(name = "lineage_id"))
	@Column(name = "source_resource_id")
	private List<UUID> sourceResourceIds = new ArrayList<>();

	/**
	 * Description of the action performed (e.g., "Merge", "JobExecution", "Copy").
	 */
	@Column(name = "action")
	private String action;

	/**
	 * Identifier of the actor (user, service account, process) performing the
	 * action.
	 */
	@Column(name = "actor_id")
	private String actorId;

	/**
	 * Timestamp when the action occurred.
	 */
	@Column(name = "timestamp", nullable = false)
	private Instant timestamp;

	// Default constructor for JPA
	protected Lineage() {
	}

	/**
	 * Constructs a new Lineage record.
	 *
	 * @param cloudResource     The target cloud resource.
	 * @param sourceResourceIds List of source resource UUIDs.
	 * @param action            The action performed.
	 * @param actorId           The ID of the actor.
	 */
	public Lineage(CloudResource cloudResource, List<UUID> sourceResourceIds, String action, String actorId) {
		this.cloudResource = Objects.requireNonNull(cloudResource, "CloudResource cannot be null");
		this.sourceResourceIds = sourceResourceIds == null ? new ArrayList<>() : new ArrayList<>(sourceResourceIds);
		this.action = action;
		this.actorId = actorId;
		this.timestamp = Instant.now();
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

	public List<UUID> getSourceResourceIds() {
		return sourceResourceIds == null ? new ArrayList<>() : new ArrayList<>(sourceResourceIds);
	}

	public void setSourceResourceIds(List<UUID> sourceResourceIds) {
		this.sourceResourceIds = sourceResourceIds == null ? new ArrayList<>() : new ArrayList<>(sourceResourceIds);
	}

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getActorId() {
		return actorId;
	}

	public void setActorId(String actorId) {
		this.actorId = actorId;
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
	}

	// --- equals, hashCode, toString ---

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		// Use instanceof check for proxy safety with JPA
		if (!(o instanceof Lineage lineage))
			return false;
		// Rely on ID for equality if persisted, otherwise object identity
		return id != null && Objects.equals(id, lineage.id);
	}

	@Override
	public int hashCode() {
		// Use a constant for entities without an ID yet, or ID's hashcode
		return id != null ? Objects.hash(id) : System.identityHashCode(this);
	}

	@Override
	public String toString() {
		return "Lineage{" +
				"id=" + id +
				", cloudResourceId=" + (cloudResource != null ? cloudResource.getId() : "null") + // Avoid infinite loop
				", sourceResourceIds=" + sourceResourceIds +
				", action='" + action + "'" +
				", actorId='" + actorId + "'" +
				", timestamp=" + timestamp +
				'}';
	}
}