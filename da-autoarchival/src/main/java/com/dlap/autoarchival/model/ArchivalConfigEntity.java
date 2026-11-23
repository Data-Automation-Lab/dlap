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

package com.dlap.autoarchival.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "dlap_archival_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalConfigEntity {

    @Id
    private Long id; // Use a fixed ID, e.g., 1L, as it's a global config

    private boolean enabled;
    private String defaultArchivalTier;
    private Long defaultRetentionDays;
    private boolean requireApprovalForArchival;
    private boolean requireApprovalForRestore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, ProviderConfigData> providerConfigs; // Store as JSON

    // Static inner class for provider config data to be serialized to JSON
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderConfigData {
        private String archivalStorageLocation;
        private String defaultRegion;
        private Map<String, String> customProperties;
    }
} 