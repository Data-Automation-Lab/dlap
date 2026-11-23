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

package com.dlap.discovery.providers.gcp.config;

import com.dlap.discovery.common.config.GCPConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service that provides access to GCP configuration.
 * This class acts as a facade to access configuration properties while respecting layer boundaries.
 */
@Service
public class GCPConfigService {
    
    private final GCPConfiguration gcpConfig;
    
    @Value("${gcp.project.id}")
    private String projectId;
    
    public GCPConfigService(GCPConfiguration gcpConfig) {
        this.gcpConfig = gcpConfig;
    }
    
    /**
     * Gets the GCP project ID.
     * @return The GCP project ID
     */
    public String getProjectId() {
        return projectId;
    }
    
    /**
     * Gets the parent folder ID.
     * @return The parent folder ID
     */
    public String getParent() {
        return gcpConfig.getParent();
    }
    
    /**
     * Gets the search text for filtering.
     * @return The search text
     */
    public String getSearchText() {
        return gcpConfig.getSearchText();
    }
    
    /**
     * Gets the number of threads to use for parallel operations.
     * @return The thread count
     */
    public int getThreads() {
        return gcpConfig.getThreads();
    }
    
    /**
     * Gets the audit dataset ID.
     * @return The audit dataset ID
     */
    public String getAuditDataset() {
        return gcpConfig.getAudit().getDataset();
    }
    
    /**
     * Gets the audit table ID.
     * @return The audit table ID
     */
    public String getAuditTable() {
        return gcpConfig.getAudit().getTable();
    }
    
    /**
     * Gets the audit activity table ID.
     * @return The audit activity table ID
     */
    public String getAuditActivityTable() {
        return gcpConfig.getAudit().getActivityTable();
    }
}