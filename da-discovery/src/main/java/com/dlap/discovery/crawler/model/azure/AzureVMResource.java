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

import com.dlap.discovery.common.model.ResourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Represents an Azure Virtual Machine resource.
 */
@Entity
@Table(name = "azure_vm_resource")
public class AzureVMResource extends AzureResource {

    /** Azure VM Size identifier (e.g., Standard_DS1_v2). */
    @Column(name = "azure_vm_size")
    private String vmSize;

    /** Operating system type (e.g., Linux, Windows). */
    @Column(name = "azure_os_type")
    private String osType;

    /**
     * No-arg constructor required by JPA.
     */
    protected AzureVMResource() {
        super();
    }

    /**
     * Constructor for AzureVMResource.
     *
     * @param resourceTypeRecord The ResourceType record for this Azure VM.
     * @param azureResourceId    The unique Azure resource ID.
     * @param name               The name of the resource.
     */
    public AzureVMResource(ResourceType resourceTypeRecord, String azureResourceId, String name) {
        super(resourceTypeRecord, azureResourceId, name);
        // Validate type specifically for VM if necessary
        if (!"azure_vm".equals(getTypeId())) {
            System.err.println("Warning: Creating AzureVMResource with unexpected ResourceType ID: " + getTypeId());
            // throw new IllegalArgumentException(...);
        }
    }

    // Removed constructors that hardcoded the old enum
    // public AzureVMResource(String id, String name, ResourceType type) { ... }
    // public AzureVMResource(String id, String name, String resourceGroup, String
    // region, String subscriptionId) { ... }

    // --- VM Specific Getters/Setters ---

    public String getVmSize() {
        return vmSize;
    }

    public void setVmSize(String vmSize) {
        this.vmSize = vmSize;
    }

    public String getOsType() {
        return osType;
    }

    public void setOsType(String osType) {
        this.osType = osType;
    }

    // Builder pattern removed as it conflicts with JPA entity lifecycle and
    // hierarchy.
    // Use constructors or factory methods and standard setters.
    // --- Builder removed ---
}