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

package com.dlap.discovery.client.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.dlap.discovery.common.model.enumeration.CloudType;
import com.dlap.discovery.common.model.enumeration.EnvType;

/**
 * A DTO for the {@code Resource} entity.
 */
public class ResourceDTO implements Serializable {

    private Long id;

    @NotNull
    private UUID uuid;

    private CloudType cloud;

    private Boolean complianceEnabled;

    private Boolean isCloudResource;

    private Boolean isOnPrem;

    @Size(min = 4, max = 100)
    private String name;

    @Size(max = 255)
    private String path;

    @NotNull
    private ZonedDateTime originDate;

    @NotNull
    private EnvType envType;

    @NotNull
    private ZonedDateTime lastUpdated;

    // Add new field for type
    private String type;

    // Add fields for BigQuery specifics
    private String projectId;
    private String datasetId;
    private String tableId;
    private String viewId;

    private String location;
    private ZonedDateTime discoveryTime;
    private Map<String, String> metadata = new HashMap<>();

    // Getters and setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public CloudType getCloud() {
        return cloud;
    }

    public void setCloud(CloudType cloud) {
        this.cloud = cloud;
    }

    public Boolean getComplianceEnabled() {
        return complianceEnabled;
    }

    public void setComplianceEnabled(Boolean complianceEnabled) {
        this.complianceEnabled = complianceEnabled;
    }

    public Boolean getIsCloudResource() {
        return isCloudResource;
    }

    public void setIsCloudResource(Boolean isCloudResource) {
        this.isCloudResource = isCloudResource;
    }

    public Boolean getIsOnPrem() {
        return isOnPrem;
    }

    public void setIsOnPrem(Boolean isOnPrem) {
        this.isOnPrem = isOnPrem;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public ZonedDateTime getOriginDate() {
        return originDate;
    }

    public void setOriginDate(ZonedDateTime originDate) {
        this.originDate = originDate;
    }

    public EnvType getEnvType() {
        return envType;
    }

    public void setEnvType(EnvType envType) {
        this.envType = envType;
    }

    public ZonedDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(ZonedDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getViewId() {
        return viewId;
    }

    public void setViewId(String viewId) {
        this.viewId = viewId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public ZonedDateTime getDiscoveryTime() {
        return discoveryTime;
    }

    public void setDiscoveryTime(ZonedDateTime discoveryTime) {
        this.discoveryTime = discoveryTime;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResourceDTO)) {
            return false;
        }

        ResourceDTO resourceDTO = (ResourceDTO) o;
        if (this.id == null) {
            return false;
        }
        return Objects.equals(this.id, resourceDTO.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.id);
    }

    @Override
    public String toString() {
        return (
            "ResourceDTO{" +
            "id=" +
            getId() +
            ", uuid='" +
            getUuid() +
            "'" +
            ", cloud='" +
            getCloud() +
            "'" +
            ", complianceEnabled='" +
            getComplianceEnabled() +
            "'" +
            ", isCloudResource='" +
            getIsCloudResource() +
            "'" +
            ", isOnPrem='" +
            getIsOnPrem() +
            "'" +
            ", name='" +
            getName() +
            "'" +
            ", path='" +
            getPath() +
            "'" +
            ", originDate='" +
            getOriginDate() +
            "'" +
            ", envType='" +
            getEnvType() +
            "'" +
            ", lastUpdated='" +
            getLastUpdated() +
            "'" +
            ", type='" +
            getType() +
            "'" +
            ", projectId='" +
            getProjectId() +
            "'" +
            ", datasetId='" +
            getDatasetId() +
            "'" +
            ", tableId='" +
            getTableId() +
            "'" +
            ", viewId='" +
            getViewId() +
            "'" +
            ", location='" +
            getLocation() +
            "'" +
            ", discoveryTime='" +
            getDiscoveryTime() +
            "'" +
            ", metadata=" +
            getMetadata() +
            "}"
        );
    }
}
