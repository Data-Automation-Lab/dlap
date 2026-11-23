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

import com.dlap.discovery.common.model.ResourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Represents a Google Cloud SQL Instance resource.
 */
@Entity
@Table(name = "cloud_sql_resource")
public class CloudSQLResource extends GcpResource {

    /** Database engine version (e.g., POSTGRES_13, MYSQL_8_0). */
    @Column(name = "sql_db_version")
    private String databaseVersion;

    /** Machine type or tier (e.g., db-f1-micro). */
    @Column(name = "sql_tier")
    private String tier;

    /** Current status of the instance (e.g., RUNNABLE, SUSPENDED). */
    @Column(name = "sql_status")
    private String status;

    /**
     * Constructor for CloudSQLResource.
     *
     * @param resourceTypeRecord The ResourceType record (must be for GCP CloudSQL).
     * @param instanceName       The GCP Cloud SQL instance name (used as
     *                           resourceId).
     * @param name               The user-defined name (often same as instanceName).
     */
    public CloudSQLResource(ResourceType resourceTypeRecord, String instanceName, String name) {
        super(resourceTypeRecord, instanceName, name);
        // Validate using the mapped typeId from the superclass
        String typeId = getTypeId();
        // TODO: Use constant for "gcp_cloudsql_instance"
        if (!"gcp_cloudsql_instance".equals(typeId)) {
            System.err.println("Warning: Creating CloudSQLResource with unexpected ResourceType ID: " + typeId);
            // Consider throwing IllegalArgumentException if type must strictly match
        }
    }

    // No-arg constructor for JPA
    protected CloudSQLResource() {
        super();
    }

    // --- Getters and Setters for CloudSQL specific fields ---

    public String getDatabaseVersion() {
        return databaseVersion;
    }

    public void setDatabaseVersion(String databaseVersion) {
        this.databaseVersion = databaseVersion;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // Inherits getResourceUrl(), toString(), getProviderSpecificType() from
    // GCPResource/CloudResource
    // Override if CloudSQL-specific behavior is needed.
}