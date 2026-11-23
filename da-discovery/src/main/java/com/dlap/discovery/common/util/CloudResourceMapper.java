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

package com.dlap.discovery.common.util;

import java.util.HashMap;

import org.springframework.stereotype.Component;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.service.CloudResourceDTO;

/**
 * Mapper class to convert between CloudResource domain model and DTO.
 * This class helps maintain the layered architecture by keeping the domain
 * model
 * separate from the discovery layer.
 */
@Component
public class CloudResourceMapper {

	public CloudResourceDTO toDTO(CloudResource resource) {
		if (resource == null) {
			return null;
		}

		CloudResourceDTO dto = new CloudResourceDTO();
		dto.setResourceId(resource.getResourceId());
		dto.setUri(resource.getUri());
		dto.setName(resource.getName());
		dto.setResourceType(resource.getResourceType().toString());
		dto.setCloudProvider(resource.getCloudProvider());
		dto.setRegion(resource.getRegion());
		dto.setZone(resource.getZone());
		dto.setProjectId(resource.getProjectId());
		dto.setAccountId(resource.getAccountId());
		dto.setCreatedAt(resource.getCreatedAt());
		dto.setUpdatedAt(resource.getUpdatedAt());
		dto.setLastDiscoveredAt(resource.getLastDiscoveredAt());
		dto.setTags(new HashMap<>(resource.getTags()));
		dto.setProperties(new HashMap<>(resource.getProperties()));
		dto.setJson(resource.getJson());
		dto.setDescription(resource.getDescription());
		dto.setParentId(resource.getParentId());
		dto.setLocation(resource.getLocation());
		dto.setHasChildren(resource.isHasChildren());

		return dto;
	}

	// Note: This method cannot create a CloudResource instance since it's abstract
	// The actual implementation should be provided by the service layer
	public void updateDomainFromDTO(CloudResource resource, CloudResourceDTO dto) {
		if (resource == null || dto == null) {
			return;
		}

		resource.setResourceId(dto.getResourceId());
		resource.setUri(dto.getUri());
		resource.setName(dto.getName());
		// Note: ResourceType should be set by the service layer
		resource.setRegion(dto.getRegion());
		resource.setZone(dto.getZone());
		resource.setProjectId(dto.getProjectId());
		resource.setAccountId(dto.getAccountId());
		resource.setCreatedAt(dto.getCreatedAt());
		resource.setUpdatedAt(dto.getUpdatedAt());
		resource.setLastDiscoveredAt(dto.getLastDiscoveredAt());
		resource.setTags(new HashMap<>(dto.getTags()));
		resource.setProperties(new HashMap<>(dto.getProperties()));
		resource.setJson(dto.getJson());
		resource.setDescription(dto.getDescription());
		resource.setParentId(dto.getParentId());
		resource.setLocation(dto.getLocation());
		resource.setHasChildren(dto.isHasChildren());
	}
}