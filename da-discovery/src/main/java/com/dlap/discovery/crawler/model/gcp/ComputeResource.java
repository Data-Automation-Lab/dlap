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
 * Represents a cloud compute resource like VM, container, or serverless
 * function.
 */
@Entity
@Table(name = "compute_resource")
public class ComputeResource extends GcpResource {

    /** Machine type identifier (e.g., n1-standard-1). */
    @Column(name = "compute_machine_type")
    private String machineType;

    /** Number of virtual CPUs. */
    @Column(name = "compute_cpu_count")
    private int cpuCount;

    /** Amount of memory in Megabytes. */
    @Column(name = "compute_memory_mb")
    private long memoryMb;

    /** Operating system identifier. */
    @Column(name = "compute_os")
    private String operatingSystem;

    /** Current status of the resource (e.g., RUNNING, STOPPED). */
    @Column(name = "compute_status")
    private String status;

    /** Network interface identifier or primary network ID. */
    @Column(name = "compute_network_id")
    private String networkId;

    /**
     * Constructor for ComputeResource.
     *
     * @param resourceTypeRecord The ResourceType record (must be for GCP Compute).
     * @param instanceId         The GCP Compute instance ID or name.
     * @param name               The user-defined name (often same as ID or from
     *                           tags).
     */
    public ComputeResource(ResourceType resourceTypeRecord, String instanceId, String name) {
        super(resourceTypeRecord, instanceId, name);
        String typeId = getTypeId();
        if (typeId == null || !typeId.startsWith("gcp_compute")) {
            System.err.println("Warning: Creating ComputeResource with unexpected ResourceType ID: " + typeId);
        }
    }

    // No-arg constructor for JPA
    protected ComputeResource() {
        super();
    }

    /**
     * Returns the provider-specific type identifier.
     * Marked transient as it uses getTypeId() which is already mapped.
     *
     * @return A string like "GCP::Compute::gcp_compute_instance"
     */
    @Override
    @Transient // Mark as transient, derived from persisted typeId
    public String getProviderSpecificType() {
        String typeId = getTypeId(); // Use mapped field
        // Adding "Compute" namespace for clarity, could be adjusted
        return typeId != null ? "GCP::Compute::" + typeId : "GCP::Compute::Unknown";
    }

    /**
     * Converts resource data to a map format suitable for catalog storage.
     * Marked Transient as it's a helper method.
     *
     * @return A map containing key resource attributes.
     */
    @Transient
    public Map<String, Object> toCatalogFormat() {
        Map<String, Object> catalogData = new HashMap<>();
        // Populate base fields (Consider creating a helper in CloudResource?)
        catalogData.put("id", getId() != null ? getId().toString() : null);
        catalogData.put("resourceId", getResourceId());
        catalogData.put("name", getName());
        catalogData.put("typeId", getTypeId());
        catalogData.put("serviceId", getServiceId());
        catalogData.put("cloudProvider", getCloudProvider());
        catalogData.put("accountId", getAccountId());
        catalogData.put("projectId", getProjectId());
        catalogData.put("region", getRegion());
        catalogData.put("zone", getZone());
        catalogData.put("location", getLocation());
        catalogData.put("createdAt", getCreatedAt() != null ? getCreatedAt().toString() : null);
        catalogData.put("updatedAt", getUpdatedAt() != null ? getUpdatedAt().toString() : null);
        catalogData.put("lastDiscoveredAt", getLastDiscoveredAt() != null ? getLastDiscoveredAt().toString() : null);
        catalogData.put("labels", getLabels());
        catalogData.put("tags", getTags());
        catalogData.put("technicalMetadata", getTechnicalMetadata());
        catalogData.put("businessMetadata", getBusinessMetadata());
        catalogData.put("compliance", getCompliance());
        // properties is transient

        // Add Compute-specific fields
        catalogData.put("computeMachineType", getMachineType());
        catalogData.put("computeCpuCount", getCpuCount());
        catalogData.put("computeMemoryMb", getMemoryMb());
        catalogData.put("computeOperatingSystem", getOperatingSystem());
        catalogData.put("computeStatus", getStatus());
        catalogData.put("computeNetworkId", getNetworkId());

        return catalogData;
    }

    /**
     * Gets a unique identifier string for the resource.
     * Marked Transient as it delegates to superclass method.
     *
     * @return A formatted string identifier.
     */
    @Transient
    public String getUniqueResourceIdentifier() {
        return getUniqueIdString();
    }

    // toString uses getters
    @Override
    public String toString() {
        return String.format(
                "ComputeResource{resourceId=%s, name='%s', typeId=%s, machineType='%s', status='%s'}",
                getResourceId(),
                getName(),
                getTypeId(), // Use mapped getter
                getMachineType(),
                getStatus());
    }

    // --- Getters and Setters for Compute specific fields ---

    public String getMachineType() {
        return machineType;
    }

    public void setMachineType(String machineType) {
        this.machineType = machineType;
    }

    public int getCpuCount() {
        return cpuCount;
    }

    public void setCpuCount(int cpuCount) {
        this.cpuCount = cpuCount;
    }

    public long getMemoryMb() {
        return memoryMb;
    }

    public void setMemoryMb(long memoryMb) {
        this.memoryMb = memoryMb;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }
}