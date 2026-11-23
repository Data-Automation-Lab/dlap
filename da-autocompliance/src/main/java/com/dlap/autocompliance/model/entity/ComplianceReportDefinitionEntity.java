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

package com.dlap.autocompliance.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(name = "compliance_report_definitions")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceReportDefinitionEntity extends AbstractAuditableEntity {

    @Id
    @Column(nullable = false, unique = true, length = 100)
    private String reportType; // e.g., "cis-gcp-foundations-v1.3"

    @Column(nullable = false, length = 255)
    private String displayName;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;

    @Column(length = 50)
    private String version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_def_frameworks", joinColumns = @JoinColumn(name = "report_type"))
    @Column(name = "framework_name")
    private List<String> applicableComplianceFrameworks;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_def_asset_types", joinColumns = @JoinColumn(name = "report_type"))
    @Column(name = "asset_type")
    private List<String> targetAssetTypes;

    // Storing parameter definitions (name and type, e.g., "gcpProjectId": "string")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> generationParametersDefinition; // Renamed for clarity from DTO's 'generationParameters'

    @Column(length = 512)
    private String detailsLink;
} 