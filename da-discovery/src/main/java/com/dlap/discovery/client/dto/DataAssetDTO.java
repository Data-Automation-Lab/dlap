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
//import com.dlap.discovery.common.model.enumeration.DataAssetType;
//import com.dlap.discovery.common.model.enumeration.EnvType;
import com.dlap.discovery.common.model.enumeration.DataAssetType;
import com.dlap.discovery.common.model.enumeration.EnvType;

/**
 * A DTO for the {@code DataAsset} entity.
 */
@SuppressWarnings("common-java:DuplicatedBlocks")
public class DataAssetDTO implements Serializable {

    private Long id;

    @NotNull(message = "must not be null")
    private UUID uuid;

    private DataAssetType type;

    @NotNull(message = "must not be null")
    @Size(min = 4, max = 300)
    private String name;

    @Size(max = 255)
    private String path;

    private EnvType envType;

    @NotNull(message = "must not be null")
    private ZonedDateTime originDate;

    @NotNull(message = "must not be null")
    private ZonedDateTime lastUpdated;

    private FileInfoDTO fileInfo;

    private TableInfoDTO table;

    private ObjInfoDTO obj;

    private UserInfoDTO createdBy;

    // New fields for BigQuery information
    private String databaseName; // For BigQuery dataset
    private String tableName; // For BigQuery table
    private String viewName; // For BigQuery view

    private String description;
    private String location;
    private String owner;
    private Map<String, String> metadata = new HashMap<>();

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

    public DataAssetType getType() {
        return type;
    }

    public void setType(DataAssetType type) {
        this.type = type;
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

    public EnvType getEnvType() {
        return envType;
    }

    public void setEnvType(EnvType envType) {
        this.envType = envType;
    }

    public ZonedDateTime getOriginDate() {
        return originDate;
    }

    public void setOriginDate(ZonedDateTime originDate) {
        this.originDate = originDate;
    }

    public ZonedDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(ZonedDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public FileInfoDTO getFileInfo() {
        return fileInfo;
    }

    public void setFileInfo(FileInfoDTO fileInfo) {
        this.fileInfo = fileInfo;
    }

    public TableInfoDTO getTable() {
        return table;
    }

    public void setTable(TableInfoDTO table) {
        this.table = table;
    }

    public ObjInfoDTO getObj() {
        return obj;
    }

    public void setObj(ObjInfoDTO obj) {
        this.obj = obj;
    }

    public UserInfoDTO getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserInfoDTO createdBy) {
        this.createdBy = createdBy;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getViewName() {
        return viewName;
    }

    public void setViewName(String viewName) {
        this.viewName = viewName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
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
        if (!(o instanceof DataAssetDTO)) {
            return false;
        }

        DataAssetDTO dataAssetDTO = (DataAssetDTO) o;
        if (this.id == null) {
            return false;
        }
        return Objects.equals(this.id, dataAssetDTO.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.id);
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "DataAssetDTO{" +
            "id=" + getId() +
            ", uuid='" + getUuid() + "'" +
            ", type='" + getType() + "'" +
            ", name='" + getName() + "'" +
            ", path='" + getPath() + "'" +
            ", envType='" + getEnvType() + "'" +
            ", originDate='" + getOriginDate() + "'" +
            ", lastUpdated='" + getLastUpdated() + "'" +
            ", fileInfo=" + getFileInfo() +
            ", table=" + getTable() +
            ", obj=" + getObj() +
            ", createdBy=" + getCreatedBy() +
            ", databaseName='" + getDatabaseName() + "'" +
            ", tableName='" + getTableName() + "'" +
            ", viewName='" + getViewName() + "'" +
            ", description='" + getDescription() + "'" +
            ", location='" + getLocation() + "'" +
            ", owner='" + getOwner() + "'" +
            ", metadata=" + getMetadata() +
            "}";
    }
}
