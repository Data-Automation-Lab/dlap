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

package com.dlap.discovery.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the optional Unity Catalog discovery source (UC-5, ADR-0029).
 * Bound from {@code cloud.provider.unity.*}. Off by default; the crawler bean is
 * only created when {@code cloud.provider.unity.enabled=true}.
 *
 * <p>This is the read side of the Unity Catalog interoperability layer: it lists
 * catalogs, schemas and tables from a Unity Catalog metastore and emits them as
 * {@code ResourceEvent}s into the discovery&#8594;catalog spine. The da-catalog
 * {@code UnityCatalogIntegration} is the complementary write side.
 */
@Component
@ConfigurationProperties(prefix = "cloud.provider.unity")
public class UnityCatalogDiscoveryProperties {

    /** Master switch. When false, no Unity Catalog discovery beans are created. */
    private boolean enabled = false;

    /**
     * Base URI of the Unity Catalog REST API, including the API path, e.g.
     * {@code http://localhost:8080/api/2.1/unity-catalog}.
     */
    private String uri = "http://localhost:8080/api/2.1/unity-catalog";

    /** Optional bearer token; sent as {@code Authorization: Bearer <token>} when set. */
    private String token;

    /** Page size used when listing catalogs, schemas and tables. */
    private int pageSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
