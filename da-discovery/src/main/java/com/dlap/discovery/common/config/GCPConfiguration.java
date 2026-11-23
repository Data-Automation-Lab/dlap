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

package com.dlap.discovery.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Google Cloud Platform services.
 * Centralizes all GCP-related configuration in one place.
 */
@Configuration
@ConfigurationProperties(prefix = "google.cloud")
public class GCPConfiguration {
    private String parent;
    private String searchText;
    private AuditConfig audit = new AuditConfig();
    private int threads = 10;
    
    /**
     * Nested configuration for audit-related properties
     */
    public static class AuditConfig {
        private String dataset;
        private String table;
        private String activityTable;
        
        // Getters and setters
        public String getDataset() { return dataset; }
        public void setDataset(String dataset) { this.dataset = dataset; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public String getActivityTable() { return activityTable; }
        public void setActivityTable(String activityTable) { this.activityTable = activityTable; }
    }
    
    // Getters and setters
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public String getSearchText() { return searchText; }
    public void setSearchText(String searchText) { this.searchText = searchText; }
    public AuditConfig getAudit() { return audit; }
    public void setAudit(AuditConfig audit) { this.audit = audit; }
    public int getThreads() { return threads; }
    public void setThreads(int threads) { this.threads = threads; }
} 