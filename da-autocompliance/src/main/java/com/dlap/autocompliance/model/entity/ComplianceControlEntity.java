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

import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "compliance_controls", indexes = {
    @Index(name = "idx_control_severity", columnList = "severity"),
    @Index(name = "idx_control_enabled", columnList = "enabled")
})
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceControlEntity extends AbstractAuditableEntity {

    @Id
    @Column(nullable = false, unique = true, length = 100)
    private String controlId;

    @Column(nullable = false, length = 255)
    private String name;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;

    @Column(length = 50)
    private String severity;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "control_frameworks", joinColumns = @JoinColumn(name = "control_id"))
    @Column(name = "framework_name")
    private List<String> applicableFrameworks;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "control_asset_types", joinColumns = @JoinColumn(name = "control_id"))
    @Column(name = "asset_type")
    private List<String> targetAssetTypes;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String remediationSteps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> evaluationParametersDefinition;

    @Column(length = 512)
    private String detailsLink;

    @Builder.Default
    private boolean enabled = true;
} 