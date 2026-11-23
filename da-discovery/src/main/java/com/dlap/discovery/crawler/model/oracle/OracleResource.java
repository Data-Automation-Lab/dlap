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

package com.dlap.discovery.crawler.model.oracle;

import java.util.HashMap;
import java.util.Map;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.annotation.JsonTypeName;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Represents a resource discovered in Oracle Cloud Infrastructure (OCI).
 * Uses OCI concepts like Compartment ID and Tenancy ID (maps to accountId).
 */
@Entity
@Table(name = "oracle_resource")
@JsonTypeName("OCI")
public class OracleResource extends CloudResource {

    // id, name, resourceType, timeCreated, timeUpdated, region, tags
    // are inherited from CloudResource and should not be re-declared.
    // tenancyId is mapped to accountId in CloudResource.
    // displayName could map to 'name' if appropriate, or be transient/unmapped.

    /** The OCID of the compartment the resource belongs to. */
    @Column(name = "oci_compartment_id")
    private String compartmentId;

    /**
     * The lifecycle state of the OCI resource (e.g., ACTIVE, INACTIVE, CREATING).
     */
    @Column(name = "oci_lifecycle_state")
    private String lifecycleState;

    /** OCI-specific defined tags or freeform tags (separate from provider tags). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "oci_resource_metadata", joinColumns = @JoinColumn(name = "resource_db_id"))
    @MapKeyColumn(name = "metadata_key")
    @Column(name = "metadata_value", length = 1024)
    private Map<String, String> oracleMetadata = new HashMap<>(); // Renamed field to avoid clash

    /**
     * Default constructor for JPA.
     */
    protected OracleResource() {
        super();
        this.oracleMetadata = new HashMap<>(); // Initialize map
    }

    /**
     * Constructor for OracleResource.
     *
     * @param resourceTypeRecord The ResourceType record for this OCI resource.
     * @param ocid               The Oracle Cloud Identifier (OCID) (maps to
     *                           resourceId).
     * @param name               The user-friendly name (maps to name).
     */
    public OracleResource(ResourceType resourceTypeRecord, String ocid, String name) {
        // Call the CloudResource constructor
        super(resourceTypeRecord, ocid, name);
        // Validate provider
        if (getCloudProviderEnum() != CloudProvider.OCI) {
            throw new IllegalArgumentException(
                    "ResourceType must be for OCI provider. Found: " + getCloudProviderEnum());
        }
        // Initialize map
        this.oracleMetadata = new HashMap<>();
    }

    // --- OCI Specific Getters/Setters ---

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    // Getter/Setter for the specific Oracle metadata map
    public Map<String, String> getOracleMetadata() {
        return oracleMetadata; // Return direct reference for JPA managed collection
    }

    public void setOracleMetadata(Map<String, String> metadata) {
        this.oracleMetadata.clear();
        if (metadata != null) {
            this.oracleMetadata.putAll(metadata);
        }
    }

    /**
     * Adds OCI-specific metadata to the resource.
     *
     * @param key   The metadata key
     * @param value The metadata value
     */
    public void addOracleMetadata(String key, String value) {
        if (this.oracleMetadata == null) {
            this.oracleMetadata = new HashMap<>();
        }
        this.oracleMetadata.put(key, value);
    }

    // --- Inherited Getters/Setters to Use ---
    // getId() -> Returns internal DB UUID
    // getResourceId() -> Returns the OCID
    // getName() -> Returns the name
    // getResourceType() -> Returns transient ResourceType record
    // getTypeId(), getServiceId(), getCloudProviderEnum() -> Mapped type components
    // getCreatedAt(), getUpdatedAt() -> Timestamps
    // getAccountId() -> Returns the Tenancy ID
    // getRegion() -> Returns the region
    // getTags() -> Returns the common provider tags

    // --- Overridden Methods ---

    // toString uses inherited getters
    @Override
    public String toString() {
        return "OracleResource{" +
                "ocid='" + getResourceId() + "\'" + // Use getResourceId() for OCID
                ", name='" + getName() + "\'" +
                ", typeId=" + getTypeId() +
                ", compartmentId='" + getCompartmentId() + "\'" +
                ", state='" + getLifecycleState() + "\'" +
                ", region='" + getRegion() + "\'" +
                ", tenancyId='" + getAccountId() + "\'" + // Use getAccountId() for Tenancy ID
                '}';
    }

    /**
     * Returns the provider-specific type identifier.
     * Marked transient as it uses getTypeId() which is already mapped.
     *
     * @return A string like "OCI::identity.compartment"
     */
    @Override
    @Transient // Mark as transient, derived from persisted typeId
    public String getProviderSpecificType() {
        String typeId = getTypeId(); // Use mapped field
        if (typeId != null) {
            // Adjust formatting if needed
            return "OCI::" + typeId;
        }
        return "OCI::Unknown"; // Fallback
    }

    // Removed redundant/conflicting methods like addTag, addMetadata (use
    // addOracleMetadata or parent getTags)
    // Removed redundant getters/setters for id, name, resourceType, etc.
}