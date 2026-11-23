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

package com.dlap.discovery.log.service.gcp.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Configuration for processing GCP logs, specifying source, filtering, and
 * checkpointing.
 */
public class GcpLogProcessingConfig {

    /**
     * Key for the BigQuery Dataset ID in logSourceDetails.
     */
    public static final String BQ_DATASET_ID = "datasetId";
    /**
     * Key for the BigQuery Table ID in logSourceDetails.
     */
    public static final String BQ_TABLE_ID = "tableId";
    /**
     * Key for the GCS Bucket Name in logSourceDetails.
     */
    public static final String GCS_BUCKET_NAME = "bucketName";
    /**
     * Key for the GCS Object Prefix (optional) in logSourceDetails.
     */
    public static final String GCS_OBJECT_PREFIX = "objectPrefix";
    /**
     * Key for the Project ID where the BQ dataset or GCS bucket resides,
     * if different from the project being analyzed.
     */
    public static final String SOURCE_PROJECT_ID = "projectId";

    private GcpLogSourceType logSourceType = GcpLogSourceType.BIGQUERY; // Default to existing behavior
    private Map<String, String> logSourceDetails; // Holds BQ/GCS specific info
    private List<String> exclusionFilters; // Filters to apply after fetching
    private Instant lastProcessedTimestamp; // Checkpoint

    // Getters and Setters

    public GcpLogSourceType logSourceType() {
        return logSourceType;
    }

    public GcpLogSourceType getLogSourceType() {
        return logSourceType;
    }

    public void setLogSourceType(GcpLogSourceType logSourceType) {
        this.logSourceType = logSourceType;
    }

    public Map<String, String> logSourceDetails() {
        return logSourceDetails;
    }

    public Map<String, String> getLogSourceDetails() {
        return logSourceDetails;
    }

    public void setLogSourceDetails(Map<String, String> logSourceDetails) {
        this.logSourceDetails = logSourceDetails;
    }

    public List<String> exclusionFilters() {
        return exclusionFilters;
    }

    public List<String> getExclusionFilters() {
        return exclusionFilters;
    }

    public void setExclusionFilters(List<String> exclusionFilters) {
        this.exclusionFilters = exclusionFilters;
    }

    public Instant lastProcessedTimestamp() {
        return lastProcessedTimestamp;
    }

    public Instant getLastProcessedTimestamp() {
        return lastProcessedTimestamp;
    }

    public void setLastProcessedTimestamp(Instant lastProcessedTimestamp) {
        this.lastProcessedTimestamp = lastProcessedTimestamp;
    }

    // --- Convenience methods for accessing details ---

    public String bigQueryDatasetId() {
        return logSourceDetails != null ? logSourceDetails.get(BQ_DATASET_ID) : null;
    }

    public String getBigQueryDatasetId() {
        return logSourceDetails != null ? logSourceDetails.get(BQ_DATASET_ID) : null;
    }

    public String bigQueryTableId() {
        return logSourceDetails != null ? logSourceDetails.get(BQ_TABLE_ID) : null;
    }

    public String getBigQueryTableId() {
        return logSourceDetails != null ? logSourceDetails.get(BQ_TABLE_ID) : null;
    }

    public String gcsBucketName() {
        return logSourceDetails != null ? logSourceDetails.get(GCS_BUCKET_NAME) : null;
    }

    public String getGcsBucketName() {
        return logSourceDetails != null ? logSourceDetails.get(GCS_BUCKET_NAME) : null;
    }

    public String gcsObjectPrefix() {
        return logSourceDetails != null ? logSourceDetails.get(GCS_OBJECT_PREFIX) : null;
    }

    public String getGcsObjectPrefix() {
        return logSourceDetails != null ? logSourceDetails.get(GCS_OBJECT_PREFIX) : null;
    }

    public String sourceProjectId() {
        return logSourceDetails != null ? logSourceDetails.get(SOURCE_PROJECT_ID) : null;
    }

    public String getSourceProjectId() {
        return logSourceDetails != null ? logSourceDetails.get(SOURCE_PROJECT_ID) : null;
    }
}
