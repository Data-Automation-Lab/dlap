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

package com.dlap.common.model.entity;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DLAP User Entity
 * 
 * Stores information about DLAP users. User authentication and primary user attributes 
 * are managed by Keycloak. This table stores DLAP-specific attributes and a link to the Keycloak user.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Entity
@Table(name = "dlap_users", schema = "dlap_common",
       indexes = {
           @Index(name = "idx_user_keycloak_subject_id", columnList = "keycloakSubjectId", unique = true),
           @Index(name = "idx_user_email", columnList = "email", unique = true),
           @Index(name = "idx_user_roles", columnList = "roles")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /**
     * Internal DLAP unique identifier for the user
     */
    @Id
    @Column(name = "user_id", columnDefinition = "UUID")
    private UUID userId;

    /**
     * Unique subject identifier from Keycloak. Essential for linking.
     */
    @NotBlank(message = "Keycloak subject ID is required")
    @Column(name = "keycloak_subject_id", length = 255, nullable = false, unique = true)
    private String keycloakSubjectId;

    /**
     * User's email address
     */
    @Email(message = "Email should be valid")
    @NotBlank(message = "Email is required")
    @Column(name = "email", length = 255, nullable = false, unique = true)
    private String email;

    /**
     * User's full name
     */
    @Size(max = 255, message = "Full name cannot exceed 255 characters")
    @Column(name = "full_name", length = 255)
    private String fullName;

    /**
     * Array of DLAP role names associated with the user (e.g., {"ADMIN", "DATA_ENGINEER"}).
     *
     * <p>Declared as {@code String[]}, not {@code List<String>}, on purpose. Hibernate 6.4
     * registers a JSON Java type for the type {@code List<String>} as soon as any entity in the
     * persistence unit maps one as jsonb ({@code CloudResource.replicaRegions}), and then
     * maps every other {@code List<String>} attribute as jsonb too. For this {@code text[]}
     * column that fails schema validation, and every write fails with "column roles is of type
     * text[] but expression is of type jsonb". The array type has no such shared registration.
     * CommonSchemaValidateTest in da-catalog guards this.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "roles", columnDefinition = "text[]")
    private String[] roles;

    /**
     * URL to the user's profile photo
     */
    @Size(max = 2048, message = "Profile photo URL cannot exceed 2048 characters")
    @Column(name = "profile_photo_url", length = 2048)
    private String profilePhotoUrl;

    /**
     * User's job title
     */
    @Size(max = 255, message = "Job title cannot exceed 255 characters")
    @Column(name = "job_title", length = 255)
    private String jobTitle;

    /**
     * User's department
     */
    @Size(max = 255, message = "Department cannot exceed 255 characters")
    @Column(name = "department", length = 255)
    private String department;

    /**
     * User's phone number
     */
    @Size(max = 50, message = "Phone number cannot exceed 50 characters")
    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    /**
     * Timestamp of user record creation
     */
    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    /**
     * Timestamp of last modification to user record
     */
    @Column(name = "last_modified_ts", nullable = false)
    private Instant lastModifiedTs;

    /**
     * Default constructor that generates a new UUID for userId
     */
    public User(String email, String fullName) {
        this.userId = UUID.randomUUID();
        this.email = email;
        this.fullName = fullName;
    }

    /**
     * JPA lifecycle callback to set creation timestamp
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdTs = now;
        this.lastModifiedTs = now;
        if (this.userId == null) {
            this.userId = UUID.randomUUID();
        }
    }

    /**
     * JPA lifecycle callback to update modification timestamp
     */
    @PreUpdate
    protected void onUpdate() {
        this.lastModifiedTs = Instant.now();
    }

    /**
     * Check if user has a specific role
     * 
     * @param role the role to check
     * @return true if user has the role, false otherwise
     */
    public boolean hasRole(String role) {
        return roles != null && Arrays.asList(roles).contains(role);
    }

    /**
     * Check if user is an admin
     * 
     * @return true if user has ADMIN role, false otherwise
     */
    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    /**
     * Check if user is a data engineer
     * 
     * @return true if user has DATA_ENGINEER role, false otherwise
     */
    public boolean isDataEngineer() {
        return hasRole("DATA_ENGINEER");
    }

    /**
     * Check if the user is in the executive/leadership band (CDO or Director).
     * DIRECTOR supersedes the legacy phantom VP role (ADR-0032).
     *
     * @return true if user has the CDO or DIRECTOR role, false otherwise
     */
    public boolean isCdoOrDirector() {
        return hasRole("CDO") || hasRole("DIRECTOR");
    }
} 