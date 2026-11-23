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

package com.dlap.policyengine.projection.unity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional Unity Catalog push-down projection sink
 * (ADR-0040 push-down mode, Q-022). Bound from
 * {@code dlap.policy.projection.unity.*}. Off by default; independent of
 * da-catalog's UC <em>metastore</em> integration ({@code dlap.catalog.unity.*},
 * ADR-0029) - this one pushes access grants, that one mirrors asset metadata.
 */
@ConfigurationProperties(prefix = "dlap.policy.projection.unity")
public class UnityCatalogGrantProperties {

    /** Enables the UC grant sink (registered in place of the logging sink). */
    private boolean enabled = false;

    /**
     * UC catalog name the securables live under. DLAP label-selector securables
     * are rendered as {@code <catalog>.<schema>} scoped grants when no concrete
     * table is resolved.
     */
    private String catalog = "dlap";

    /** UC schema name within the catalog. */
    private String schema = "default";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
}
