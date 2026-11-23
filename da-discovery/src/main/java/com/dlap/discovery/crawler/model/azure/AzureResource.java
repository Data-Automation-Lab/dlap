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

package com.dlap.discovery.crawler.model.azure;

import java.util.HashMap;
import java.util.Map;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.annotation.JsonTypeName;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Represents a generic resource discovered in Microsoft Azure Cloud.
 * Serves as a base for more specific Azure resource types (e.g., VM).
 */
@Entity
@Table(name = "azure_resource")
@JsonTypeName("AZURE")
public class AzureResource extends CloudResource {

    // Fields like displayName (maps to 'name'), timeCreated (maps to 'createdAt'),
    // timeUpdated (maps to 'updatedAt'), subscriptionId (maps to 'accountId')
    // are handled by the CloudResource parent.

    /** The Azure resource group the resource belongs to. */
    @Column(name = "azure_resource_group")
    private String resourceGroup;

    /** The provisioning state of the resource (e.g., Succeeded, Failed). */
    @Column(name = "azure_provisioning_state")
    private String provisioningState;

    /** The Azure subscription ID. */
    @Column(name = "azure_subscription_id")
    private String subscriptionId;

    /** Azure-specific properties not fitting common fields. Marked Transient. */
    @Transient // Persisting Map<String, Object> requires custom handling (e.g., JSON)
    private Map<String, Object> azureSpecificProperties = new HashMap<>();

    /**
     * Constructor for AzureResource.
     *
     * @param resourceTypeRecord The ResourceType record for this Azure resource.
     * @param azureResourceId    The unique Azure resource ID.
     * @param name               The name of the resource.
     */
    public AzureResource(ResourceType resourceTypeRecord, String azureResourceId, String name) {
        super(resourceTypeRecord, azureResourceId, name);
        // Validate provider
        if (getCloudProviderEnum() != CloudProvider.AZURE) {
            throw new IllegalArgumentException(
                    "ResourceType must be for AZURE provider. Found: " + getCloudProviderEnum());
        }
        // Initialize transient map
        this.azureSpecificProperties = new HashMap<>();
    }

    /**
     * No-arg constructor required by JPA.
     */
    protected AzureResource() {
        super();
        // Initialize transient map
        this.azureSpecificProperties = new HashMap<>();
    }

    // --- Getters and Setters for Azure specific fields ---

    // Inherit getDisplayName() -> use getName()
    // Inherit getTimeCreated() -> use getCreatedAt()
    // Inherit getTimeUpdated() -> use getUpdatedAt()
    // Inherit getSubscriptionId() -> use getAccountId()

    public String getResourceGroup() {
        return resourceGroup;
    }

    public void setResourceGroup(String resourceGroup) {
        this.resourceGroup = resourceGroup;
    }

    public String getProvisioningState() {
        return provisioningState;
    }

    public void setProvisioningState(String provisioningState) {
        this.provisioningState = provisioningState;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    @Transient
    public Map<String, Object> getAzureSpecificProperties() {
        // Return defensive copy for transient map?
        return azureSpecificProperties == null ? new HashMap<>() : new HashMap<>(azureSpecificProperties);
    }

    @Transient
    public void setAzureSpecificProperties(Map<String, Object> properties) {
        this.azureSpecificProperties = properties == null ? new HashMap<>() : new HashMap<>(properties);
    }

    @Transient
    public void addAzureSpecificProperty(String key, Object value) {
        if (this.azureSpecificProperties == null) {
            this.azureSpecificProperties = new HashMap<>();
        }
        this.azureSpecificProperties.put(key, value);
    }

    // toString uses inherited getters where applicable
    @Override
    public String toString() {
        return "AzureResource{" +
                "resourceId='" + getResourceId() + "\'" +
                ", name='" + getName() + "\'" +
                ", typeId=" + getTypeId() +
                ", resourceGroup='" + getResourceGroup() + "\'" +
                ", state='" + getProvisioningState() + "\'" +
                ", region='" + getRegion() + "\'" +
                ", subscription='" + getAccountId() + "\'" + // Use getAccountId()
                '}';
    }

    /**
     * Returns the provider-specific type identifier.
     * Marked transient as it uses getTypeId() which is already mapped.
     *
     * @return A string like "AZURE::Microsoft.Compute/virtualMachines"
     */
    @Override
    @Transient // Mark as transient, derived from persisted typeId
    public String getProviderSpecificType() {
        String typeId = getTypeId(); // Use mapped field
        if (typeId != null) {
            // Azure type IDs often have provider namespace (e.g.,
            // Microsoft.Compute/virtualMachines)
            // Adjust formatting as needed
            return "AZURE::" + typeId;
        }
        return "AZURE::Unknown"; // Fallback
    }
}