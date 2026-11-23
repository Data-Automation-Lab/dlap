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

package com.dlap.adminservice.model;

import com.dlap.adminservice.model.enums.CloudProviderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "dlap_cloud_connection")
public class CloudConnectionEntity extends AbstractAuditableEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 50)
    private CloudProviderType providerType;

    // BOOT-01: Hibernate's own JSON mapping (its Jackson 2 format mapper) replaces the JsonType of
    // hypersistence-utils-hibernate-62, which does not run on Hibernate 7. A Map<String, String> reads
    // and writes the same jsonb text either way (ProdProfileContextTest#cloudConnectionParametersRoundTripAsJsonb).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connection_parameters", columnDefinition = "jsonb")
    private Map<String, String> connectionParameters;

    // Ciphertext stored as TEXT (not @Lob/oid): @Lob maps String to a Postgres
    // large object (oid), which both fails ddl-auto=validate against a text column
    // and makes ddl-auto=update attempt an impossible text->oid cast.
    @Column(name = "encrypted_credentials", columnDefinition = "TEXT")
    private String encryptedCredentials;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_connection_test_at")
    private LocalDateTime lastConnectionTestAt;

    @Column(name = "last_connection_test_status", length = 50)
    private String lastConnectionTestStatus; // e.g., SUCCESS, FAILED
} 