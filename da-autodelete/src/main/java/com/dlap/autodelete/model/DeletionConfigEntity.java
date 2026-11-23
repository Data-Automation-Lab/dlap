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

package com.dlap.autodelete.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
// import org.hibernate.annotations.JdbcTypeCode; // Not needed if not using complex JSON types directly here
// import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "dlap_deletion_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeletionConfigEntity {

    @Id
    private Long id; // Use a fixed ID, e.g., 1L, as it's a global config

    private boolean enabled;
    private boolean softDeleteByDefault;
    private Long defaultSoftDeleteRetentionDays;
    private boolean requireApprovalForHardDelete;
    private boolean requireApprovalForSoftDelete;

    @ElementCollection(fetch = FetchType.EAGER) // Store as a collection of strings
    @CollectionTable(name = "dlap_deletion_config_notification_emails", joinColumns = @JoinColumn(name = "config_id"))
    @Column(name = "email")
    private List<String> notificationEmailsOnError;

    // If you add more complex configurations like per-provider or per-tag rules that are better stored as JSON,
    // you can use @JdbcTypeCode(SqlTypes.JSON) similar to da-autoarchival's ProviderConfigData.
    // For example:
    // @JdbcTypeCode(SqlTypes.JSON)
    // @Column(columnDefinition = "jsonb")
    // private Map<String, DeletionStrategyData> deletionStrategies; 
    // public static class DeletionStrategyData { /* fields */ }
} 