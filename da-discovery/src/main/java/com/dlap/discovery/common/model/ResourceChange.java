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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.GenericGenerator;

import com.dlap.discovery.common.model.enums.CloudProvider;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Represents a change to a cloud resource detected through audit logs or other
 * means.
 * This is typically used for historical tracking of modifications.
 */
@Entity
@Table(name = "resource_change")
public class ResourceChange {

    /**
     * Type of change that occurred to the resource.
     */
    public enum ChangeType {
        CREATE, // Resource was created
        UPDATE, // Resource was updated
        DELETE, // Resource was deleted
        ACCESS, // Resource was accessed
        PERMISSION, // Resource permissions were changed
        UNKNOWN // Unknown change type
    }

    /**
     * Unique identifier for this change record.
     */
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * The provider-specific ID of the resource that was changed.
     * This usually doesn't link directly to CloudResource.id, but to its business
     * key.
     */
    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    // --- ResourceType Components ---
    // Store components instead of the ResourceType record directly.
    @Transient
    private ResourceType resourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "cloud_provider") // Store provider from ResourceType
    private CloudProvider cloudProvider;

    @Column(name = "service_id") // Store service ID from ResourceType
    private String serviceId;

    @Column(name = "type_id") // Store type ID from ResourceType
    private String typeId;
    // --- End ResourceType Components ---

    /**
     * The type of change that occurred.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;

    /**
     * Timestamp when the change occurred.
     */
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    /**
     * Identifier of the actor (user, service account) who performed the change.
     */
    @Column(name = "actor")
    private String actor;

    /**
     * Email address associated with the actor, if available.
     */
    @Column(name = "actor_email")
    private String actorEmail;

    /**
     * Project ID associated with the change event (especially relevant for GCP).
     */
    @Column(name = "project_id") // Consider making this specific to provider or using a generic account_id?
    private String projectId;

    /**
     * Additional details about the change event (e.g., specific fields modified).
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "resource_change_details", joinColumns = @JoinColumn(name = "change_id"))
    @MapKeyColumn(name = "detail_key")
    @Column(name = "detail_value", length = 2048) // Adjust length as needed
    private Map<String, String> details = new HashMap<>();

    // --- Constructors and Existing Methods ---

    /**
     * Default constructor for JPA.
     */
    protected ResourceChange() {
    }

    /**
     * Constructor with essential fields.
     * 
     * @param resourceId   Provider-specific ID of the changed resource.
     * @param resourceType The ResourceType record describing the resource.
     * @param changeType   Type of change.
     * @param timestamp    When the change occurred.
     * @param actor        Who made the change.
     */
    public ResourceChange(String resourceId, ResourceType resourceType, ChangeType changeType,
            Instant timestamp, String actor) {
        this.resourceId = resourceId;
        setResourceTypeInternal(resourceType); // Use helper to set components
        this.changeType = changeType;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.actor = actor;
    }

    // Helper to set ResourceType components from the record
    private void setResourceTypeInternal(ResourceType resourceType) {
        this.resourceType = resourceType; // Keep transient reference
        if (resourceType != null) {
            this.typeId = resourceType.id();
            if (resourceType.service() != null) {
                this.serviceId = resourceType.service().id();
                this.cloudProvider = resourceType.service().provider(); // Uses new enum
            } else {
                this.serviceId = null;
                this.cloudProvider = null;
            }
        } else {
            this.typeId = null;
            this.serviceId = null;
            this.cloudProvider = null;
        }
    }

    // --- Getters and Setters ---

    public UUID getId() {
        return id;
    }
    // No setId for generated ID

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    // Getter for transient ResourceType - reconstruct if needed (optional)
    @Transient
    public ResourceType getResourceType() {
        // Return transient field, reconstruction logic can be added if necessary
        // but might require CloudService record/constructor access
        return resourceType;
    }

    // Setter for ResourceType - uses helper to set components
    public void setResourceType(ResourceType resourceType) {
        setResourceTypeInternal(resourceType);
    }

    // Getters for mapped components
    public CloudProvider getCloudProvider() {
        return cloudProvider;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getTypeId() {
        return typeId;
    }
    // No public setters for components, set via setResourceType

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public void setActorEmail(String actorEmail) {
        this.actorEmail = actorEmail;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    // Use direct reference for JPA managed collection
    public Map<String, String> getDetails() {
        return details;
    }

    // Setter to replace content of the managed map
    public void setDetails(Map<String, String> details) {
        this.details.clear();
        if (details != null) {
            this.details.putAll(details);
        }
    }

    /**
     * Adds a detail to the details map.
     * 
     * @param key   Detail key
     * @param value Detail value
     */
    public void addDetail(String key, String value) {
        if (this.details == null) {
            // Should not happen with field initializer, but defensively:
            this.details = new HashMap<>();
        }
        this.details.put(key, value);
    }

    // --- equals, hashCode, toString ---

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ResourceChange that))
            return false;
        // Use ID for equality check once persisted
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hash(id) : System.identityHashCode(this);
    }

    @Override
    public String toString() {
        // Use mapped fields for consistency
        return "ResourceChange{" +
                "id=" + id +
                ", resourceId='" + resourceId + "\'" +
                ", cloudProvider=" + cloudProvider +
                ", typeId='" + typeId + "\'" +
                ", changeType=" + changeType +
                ", timestamp=" + timestamp +
                ", actor='" + actor + "\'" +
                '}';
    }
}