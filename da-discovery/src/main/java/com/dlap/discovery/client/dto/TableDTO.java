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

import java.math.BigInteger;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Data Transfer Object for BigQuery tables.
 */
public class TableDTO {
    private String tableId;
    private String datasetId;
    private String projectId;
    private ZonedDateTime creationTime;
    private ZonedDateTime lastModifiedTime;
    private BigInteger rowCount;
    private BigInteger sizeBytes;
    private Map<String, String> labels = new HashMap<>();

    // Getters and setters
    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public ZonedDateTime getCreationTime() {
        return creationTime;
    }

    public void setCreationAt(ZonedDateTime creationTime) {
        this.creationTime = creationTime;
    }

    public ZonedDateTime getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(ZonedDateTime lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public BigInteger getRowCount() {
        return rowCount;
    }

    public void setRowCount(BigInteger rowCount) {
        this.rowCount = rowCount;
    }

    public BigInteger getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(BigInteger sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels != null ? labels : new HashMap<>();
    }

    @Override
    public String toString() {
        return String.format("TableDTO{tableId='%s', datasetId='%s', projectId='%s'}",
                tableId, datasetId, projectId);
    }
}