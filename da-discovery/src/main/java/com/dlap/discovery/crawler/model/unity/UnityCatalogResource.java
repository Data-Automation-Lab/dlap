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

package com.dlap.discovery.crawler.model.unity;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.annotation.JsonTypeName;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Represents a Unity Catalog table discovered from a Unity Catalog metastore
 * (UC-5, ADR-0029). A concrete entity within the {@link CloudResource}
 * hierarchy whose {@code resourceId} is the three-level Unity Catalog name
 * {@code catalog.schema.table}. Its {@code uri} is the canonical
 * {@code uc://{metastore}/{catalog}/{schema}/{table}} (ADR-0047), which the
 * crawler stores once it knows the metastore: a three-part name is unique only
 * inside a metastore, so the constructor does not write one.
 *
 * <p>Unity Catalog is treated as a standalone provider ({@link CloudProvider#UNITY}),
 * not a Databricks-specific integration, in keeping with its open-source status.
 */
@Entity
@Table(name = "unity_catalog_resource")
@JsonTypeName("UNITY")
public class UnityCatalogResource extends CloudResource {

    /** Registry type id for a Unity Catalog table (see cloud-hierarchy config). */
    public static final String TABLE_TYPE_ID = "unity_table";

    private String catalogName;
    private String schemaName;
    private String tableName;

    /**
     * No-arg constructor for JPA and serialization frameworks.
     */
    protected UnityCatalogResource() {
        super();
    }

    /**
     * Constructor for a Unity Catalog table resource.
     *
     * @param resourceTypeRecord The ResourceType record (must be for the UNITY provider).
     * @param catalogName        The Unity Catalog catalog name.
     * @param schemaName         The schema name within the catalog.
     * @param tableName          The table name within the schema.
     */
    public UnityCatalogResource(ResourceType resourceTypeRecord, String catalogName, String schemaName,
            String tableName) {
        super(resourceTypeRecord, fullName(catalogName, schemaName, tableName), tableName);
        if (getCloudProviderEnum() != CloudProvider.UNITY) {
            throw new IllegalArgumentException(
                    "ResourceType must be for the UNITY provider. Found: " + getCloudProviderEnum());
        }
        this.catalogName = catalogName;
        this.schemaName = schemaName;
        this.tableName = tableName;
        // No uri here: the canonical URI needs the metastore, which the crawler knows.
    }

    /**
     * Builds the Unity Catalog three-level name {@code catalog.schema.table}.
     *
     * @param catalogName the catalog name
     * @param schemaName  the schema name
     * @param tableName   the table name
     * @return the fully-qualified Unity Catalog name
     */
    public static String fullName(String catalogName, String schemaName, String tableName) {
        return catalogName + "." + schemaName + "." + tableName;
    }

    public String getCatalogName() {
        return catalogName;
    }

    public void setCatalogName(String catalogName) {
        this.catalogName = catalogName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    @Override
    @Transient
    public String getProviderSpecificType() {
        String typeId = getTypeId();
        return typeId != null ? "UNITY::" + typeId : "UNITY::Unknown";
    }

    @Override
    public String toString() {
        return "UnityCatalogResource[resourceId=" + getResourceId()
                + ", name=" + getName()
                + ", typeId=" + getTypeId()
                + ", catalog=" + catalogName
                + ", schema=" + schemaName
                + ", table=" + tableName + "]";
    }
}
