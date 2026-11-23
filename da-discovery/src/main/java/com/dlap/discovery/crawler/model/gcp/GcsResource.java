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

import java.util.HashMap;
import java.util.Map;

import com.dlap.discovery.common.model.ResourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Represents a Google Cloud Storage (GCS) bucket or object resource.
 * Note: Original class seemed to mix concepts (storage, db, files). This
 * focuses on GCS.
 */
@Entity
@Table(name = "gcs_resource")
// Discriminator value needs to be specific to the type being represented.
// If this class covers both buckets and objects, this strategy might need
// refinement
// (e.g., separate classes or a different discriminator logic).
// Let's use a placeholder indicating it's GCS-related, but this might need
// adjustment based on actual type IDs used.
public class GcsResource extends GcpResource {

    /** Size of the resource (object) in bytes. */
    @Column(name = "gcs_size_bytes")
    private long sizeBytes;

    /** Content type of the GCS object. */
    @Column(name = "gcs_content_type")
    private String contentType;

    /** Storage class (e.g., STANDARD, NEARLINE). */
    @Column(name = "gcs_storage_class")
    private String storageClass;

    /** Flag indicating if the resource has public access. */
    @Column(name = "gcs_is_public")
    private boolean isPublic;

    /** Parent resource identifier (e.g., bucket name for an object). */
    @Column(name = "gcs_parent_resource")
    private String parentResource;

    /** Example metric: Access count (potentially move to UsageStats). */
    @Transient // Access count is likely better tracked in CloudResourceUsageStats
    private int accessCount;

    /**
     * Constructor for GcsResource.
     * 
     * @param resourceTypeRecord The ResourceType record (e.g., for gcp_gcs_bucket
     *                           or gcp_gcs_object).
     * @param bucketOrObjectName The GCS bucket name or object name (used as
     *                           resourceId).
     * @param name               The user-defined name (often same as ID).
     */
    public GcsResource(ResourceType resourceTypeRecord, String bucketOrObjectName, String name) {
        super(resourceTypeRecord, bucketOrObjectName, name);
        if (resourceTypeRecord == null || !resourceTypeRecord.id().startsWith("gcp_gcs")) {
            System.err.println("Warning: Creating GcsResource with unexpected ResourceType: " +
                    (resourceTypeRecord != null ? resourceTypeRecord.id() : "null"));
        }
    }

    /**
     * Default constructor for JPA.
     */
    protected GcsResource() {
        super();
    }

    /**
     * Increments the transient access count.
     * Note: Usage stats should ideally be managed via CloudResourceUsageStats
     * entity.
     */
    @Transient
    public void incrementAccessCount() {
        this.accessCount++;
    }

    /**
     * Creates a Map representation, useful for specific export formats.
     * Marked Transient as it's a helper method, not a persisted property.
     *
     * @return Map representation of the resource.
     */
    @Transient
    public Map<String, Object> toCatalogFormat() {
        Map<String, Object> catalogData = new HashMap<>();
        catalogData.put("id", getId() != null ? getId().toString() : null);
        catalogData.put("resourceId", getResourceId());
        catalogData.put("name", getName());
        catalogData.put("resourceTypeId", getResourceType() != null ? getResourceType().id() : null);
        catalogData.put("resourceTypeDisplayName", getResourceType() != null ? getResourceType().displayName() : null);
        catalogData.put("serviceId",
                getResourceType() != null && getResourceType().service() != null ? getResourceType().service().id()
                        : null);
        catalogData.put("cloudProvider", getCloudProvider());
        catalogData.put("accountId", getAccountId());
        catalogData.put("projectId", getProjectId());
        catalogData.put("region", getRegion());
        catalogData.put("location", getLocation());
        catalogData.put("createdAt", getCreatedAt() != null ? getCreatedAt().toString() : null);
        catalogData.put("updatedAt", getUpdatedAt() != null ? getUpdatedAt().toString() : null);
        catalogData.put("lastDiscoveredAt", getLastDiscoveredAt() != null ? getLastDiscoveredAt().toString() : null);
        catalogData.put("labels", getLabels());
        catalogData.put("tags", getTags());
        catalogData.put("properties", getProperties());

        catalogData.put("sizeBytes", getSizeBytes());
        catalogData.put("contentType", getContentType());
        catalogData.put("storageClass", getStorageClass());
        catalogData.put("isPublic", isPublic());
        catalogData.put("parentResource", getParentResource());
        catalogData.put("accessCount", getAccessCount());

        return catalogData;
    }

    /**
     * Gets the unique identifier string (alias).
     * Marked transient as it delegates to a transient method in the superclass.
     *
     * @return Unique identifier string.
     */
    @Transient
    public String getUniqueResourceIdentifier() {
        return getUniqueIdString();
    }

    @Override
    public String toString() {
        return String.format(
                "GcsResource{id=%s, name='%s', type=%s, size=%d, isPublic=%s}",
                getResourceId(),
                getName(),
                (getResourceType() != null ? getResourceType().id() : "null"),
                getSizeBytes(),
                isPublic());
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStorageClass() {
        return storageClass;
    }

    public void setStorageClass(String storageClass) {
        this.storageClass = storageClass;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public String getParentResource() {
        return parentResource;
    }

    public void setParentResource(String parentResource) {
        this.parentResource = parentResource;
    }

    /**
     * Gets the transient access count.
     * Note: Usage stats should ideally be managed via CloudResourceUsageStats
     * entity.
     *
     * @return the access count
     */
    @Transient
    public int getAccessCount() {
        return accessCount;
    }

    /**
     * Sets the transient access count.
     * Note: Usage stats should ideally be managed via CloudResourceUsageStats
     * entity.
     *
     * @param accessCount the access count to set
     */
    @Transient
    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }
}