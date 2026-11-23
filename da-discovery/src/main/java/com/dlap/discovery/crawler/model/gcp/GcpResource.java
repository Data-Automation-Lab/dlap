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

package com.dlap.discovery.crawler.model.gcp;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.annotation.JsonTypeName;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Represents a Google Cloud Platform resource.
 * This is a concrete entity type within the CloudResource hierarchy.
 */
@Entity
@Table(name = "gcp_resource")
@JsonTypeName("GCP")
public class GcpResource extends CloudResource {

    /**
     * Constructor for GCPResource.
     * 
     * @param resourceTypeRecord The ResourceType record (must be for GCP).
     * @param gcpResourceId      The unique GCP resource ID (e.g., instance name,
     *                           bucket name).
     * @param name               The user-friendly name (often same as ID for GCP,
     *                           or from labels/tags).
     */
    public GcpResource(ResourceType resourceTypeRecord, String gcpResourceId, String name) {
        super(resourceTypeRecord, gcpResourceId, name);
        if (getCloudProviderEnum() != CloudProvider.GCP) {
            throw new IllegalArgumentException(
                    "ResourceType must be for GCP provider. Found: " + getCloudProviderEnum());
        }
    }

    /**
     * No-arg constructor for frameworks
     */
    protected GcpResource() {
        super();
    }

    /**
     * Generates a Console URL for the specific GCP resource.
     * This method relies on potentially transient fields (like properties) and may
     * not work
     * reliably for entities loaded from the database unless those fields are
     * populated.
     * 
     * @return A String URL or null if essential information is missing.
     */
    @Transient
    public String getResourceUrl() {
        String resourceIdStr = getResourceId();
        String projId = getProjectId();
        String typeId = getTypeId();

        if (typeId == null || resourceIdStr == null || projId == null) {
            return null;
        }

        String baseUrl = "https://console.cloud.google.com/";
        String location = getRegion() != null ? getRegion() : getZone();

        if ("gcp_gcs_bucket".equals(typeId)) {
            return baseUrl + "storage/browser/" + resourceIdStr + "?project=" + projId;
        } else if ("gcp_cloudsql_instance".equals(typeId)) {
            return baseUrl + "sql/instances/" + resourceIdStr + "/overview?project=" + projId;
        } else if ("gcp_compute_instance".equals(typeId)) {
            String zone = getZone();
            if (zone == null)
                return null;
            return baseUrl + "compute/instancesDetail/zones/" + zone + "/instances/" + resourceIdStr + "?project="
                    + projId;
        } else if ("gcp_bigquery_table".equals(typeId)) {
            String datasetId = (String) getProperties().get("datasetId");
            if (datasetId == null) {
                System.err.println("Warning: datasetId not found in properties for BigQuery table URL generation.");
                return null;
            }
            return baseUrl + "bigquery?project=" + projId + "&p=" + projId + "&d=" + datasetId + "&t=" + resourceIdStr
                    + "&page=table";
        } else if ("gcp_bigquery_dataset".equals(typeId)) {
            return baseUrl + "bigquery?project=" + projId + "&p=" + projId + "&d=" + resourceIdStr + "&page=dataset";
        } else {
            return baseUrl + "home/dashboard?project=" + projId;
        }
    }

    @Override
    public String toString() {
        return "GCPResource [resourceId=" + getResourceId() + ", name=" + getName() + ", typeId=" + getTypeId()
                + ", projectId=" + getProjectId() + ", location=" + getLocation() + "]";
    }

    @Override
    @Transient
    public String getProviderSpecificType() {
        String typeId = getTypeId();
        return typeId != null ? "GCP::" + typeId : "GCP::Unknown";
    }

}