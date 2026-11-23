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

package com.dlap.discovery.common.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object for cloud resources.
 * This class is used in the discovery layer to avoid direct dependency on the
 * domain model.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudResourceDTO {

	private UUID id;
	private String resourceId;
	private String uri;
	private String name;
	private String resourceType;
	private String cloudProvider;
	private String region;
	private String zone;
	private String projectId;
	private String accountId;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant lastDiscoveredAt;
	private Map<String, String> tags = new HashMap<>();
	private Map<String, Object> properties = new HashMap<>();
	private String json;
	private String description;
	private String parentId;
	private String location;
	private boolean hasChildren;

	public CloudResourceDTO() {
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getResourceId() {
		return resourceId;
	}

	public void setResourceId(String resourceId) {
		this.resourceId = resourceId;
	}

	public String getUri() {
		return uri;
	}

	public void setUri(String uri) {
		this.uri = uri;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getResourceType() {
		return resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
	}

	public String getCloudProvider() {
		return cloudProvider;
	}

	public void setCloudProvider(String cloudProvider) {
		this.cloudProvider = cloudProvider;
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
	}

	public String getZone() {
		return zone;
	}

	public void setZone(String zone) {
		this.zone = zone;
	}

	public String getProjectId() {
		return projectId;
	}

	public void setProjectId(String projectId) {
		this.projectId = projectId;
	}

	public String getAccountId() {
		return accountId;
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

	public Instant getLastDiscoveredAt() {
		return lastDiscoveredAt;
	}

	public void setLastDiscoveredAt(Instant lastDiscoveredAt) {
		this.lastDiscoveredAt = lastDiscoveredAt;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public void setTags(Map<String, String> tags) {
		this.tags = tags;
	}

	public Map<String, Object> getProperties() {
		return properties;
	}

	public void setProperties(Map<String, Object> properties) {
		this.properties = properties;
	}

	@JsonProperty("rawResource")
	public String getJson() {
		return json;
	}

	@JsonIgnore
	public void setJson(String json) {
		this.json = json;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getParentId() {
		return parentId;
	}

	public void setParentId(String parentId) {
		this.parentId = parentId;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public boolean isHasChildren() {
		return hasChildren;
	}

	public void setHasChildren(boolean hasChildren) {
		this.hasChildren = hasChildren;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		CloudResourceDTO that = (CloudResourceDTO) o;
		return hasChildren == that.hasChildren &&
				Objects.equals(id, that.id) &&
				Objects.equals(resourceId, that.resourceId) &&
				Objects.equals(uri, that.uri) &&
				Objects.equals(name, that.name) &&
				Objects.equals(resourceType, that.resourceType) &&
				Objects.equals(cloudProvider, that.cloudProvider) &&
				Objects.equals(region, that.region) &&
				Objects.equals(zone, that.zone) &&
				Objects.equals(projectId, that.projectId) &&
				Objects.equals(accountId, that.accountId) &&
				Objects.equals(createdAt, that.createdAt) &&
				Objects.equals(updatedAt, that.updatedAt) &&
				Objects.equals(lastDiscoveredAt, that.lastDiscoveredAt) &&
				Objects.equals(tags, that.tags) &&
				Objects.equals(properties, that.properties) &&
				Objects.equals(json, that.json) &&
				Objects.equals(description, that.description) &&
				Objects.equals(parentId, that.parentId) &&
				Objects.equals(location, that.location);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, resourceId, uri, name, resourceType, cloudProvider,
				region, zone, projectId, accountId, createdAt, updatedAt, lastDiscoveredAt,
				tags, properties, json, description, parentId, location, hasChildren);
	}

	@Override
	public String toString() {
		return "CloudResourceDTO{" +
				"id=" + id +
				", resourceId='" + resourceId + '\'' +
				", name='" + name + '\'' +
				", resourceType='" + resourceType + '\'' +
				", cloudProvider='" + cloudProvider + '\'' +
				", region='" + region + '\'' +
				", projectId='" + projectId + '\'' +
				", accountId='" + accountId + '\'' +
				'}';
	}
}