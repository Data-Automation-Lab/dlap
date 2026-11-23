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
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;

/**
 * Domain model representing a BigQuery resource (dataset or table).
 * Corresponds to typeIds "gcp_bigquery_dataset" or "gcp_bigquery_table".
 */
@Entity
@Table(name = "bigquery_resource")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "resource_type", discriminatorType = DiscriminatorType.STRING)
@DiscriminatorValue("dataset")
public class BigQueryResource extends GcpResource {

    private static final String DATASET_TYPE_ID = "gcp_bigquery_dataset";
    private static final String TABLE_TYPE_ID = "gcp_bigquery_table";

    /**
     * Default constructor for JPA and frameworks.
     */
    public BigQueryResource() {
        super(); // Call parent constructor
    }

    /**
     * Constructor for BigQueryResource (Dataset or Table).
     *
     * @param resourceTypeRecord The ResourceType record (must be for
     *                           gcp_bigquery_dataset or gcp_bigquery_table).
     * @param resourceId         The BigQuery dataset ID or table ID (used as resourceId).
     * @param name               The user-defined name (often same as resourceId).
     */
    public BigQueryResource(ResourceType resourceTypeRecord, String resourceId, String name) {  
        super(resourceTypeRecord, resourceId, name);
        // Validate using the mapped typeId from the superclass
        String typeId = getTypeId();
        if (!DATASET_TYPE_ID.equals(typeId) && !TABLE_TYPE_ID.equals(typeId)) {
            throw new IllegalArgumentException(
                "Invalid ResourceType ID for BigQueryResource: " + typeId +
                ". Expected: " + DATASET_TYPE_ID + " or " + TABLE_TYPE_ID);
        }
    }

    // toString uses inherited getters
    @Override
    public String toString() {
        return "BigQueryResource[resourceId=" + getResourceId() +
                ", name=" + getName() +
                ", typeId=" + getTypeId() +
                ", projectId=" + getProjectId() +
                ", location=" + getLocation() + "]";
    }

    // Getters/Setters for specific BigQuery fields can be added here if needed
    // (e.g., table-specific schema or partition details stored in metadata).
}