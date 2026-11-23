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

package com.dlap.catalog.integration.unity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration for the optional Unity Catalog integration (ADR-0029).
 * Bound from {@code dlap.catalog.unity.*}. Off by default.
 */
@Data
@ConfigurationProperties(prefix = "dlap.catalog.unity")
public class UnityCatalogProperties {

    /** Master switch. When false, no Unity Catalog beans are created. */
    private boolean enabled = false;

    /**
     * Base URI of the Unity Catalog REST API, including the API path, e.g.
     * {@code http://localhost:8081/api/2.1/unity-catalog}.
     */
    private String uri = "http://localhost:8081/api/2.1/unity-catalog";

    /** Optional bearer token; sent as {@code Authorization: Bearer <token>} when set. */
    private String token = "";

    /** Target catalog name for published DLAP assets. */
    private String catalog = "dlap";

    /** Target schema name within the catalog. */
    private String schema = "default";
}
