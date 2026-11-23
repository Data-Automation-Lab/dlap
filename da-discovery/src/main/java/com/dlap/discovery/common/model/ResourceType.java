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

/**
 * Represents a specific type of resource within a cloud service.
 * For example, a BigQuery Table within the BigQuery service in GCP.
 *
 * @param id          A unique identifier string for the resource type (e.g.,
 *                    "gcp_bigquery_table").
 * @param displayName A user-friendly name for the resource type (e.g.,
 *                    "BigQuery Table").
 * @param service     The cloud service this resource type belongs to.
 */
public record ResourceType(String id, String displayName, CloudService service) {
    // Records automatically provide immutable fields, constructor, equals,
    // hashCode, and toString.

    /**
     * Static method to create a ResourceType from a string identifier.
     * 
     * @param typeId The resource type identifier
     * @return A new ResourceType instance with the given ID
     * 
     * @deprecated Do not use this for registry or job creation. Always use
     *             CloudHierarchyRegistry to get the canonical ResourceType instance.
     */
    @Deprecated
    public static ResourceType valueOf(String typeId) {
        if (typeId == null || typeId.isEmpty()) {
            throw new IllegalArgumentException("Type ID cannot be null or empty");
        }
        return new ResourceType(typeId, typeId, null);
    }
}