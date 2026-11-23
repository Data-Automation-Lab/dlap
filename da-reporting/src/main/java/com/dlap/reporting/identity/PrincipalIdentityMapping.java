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

package com.dlap.reporting.identity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One entry of the principal identity map ({@code V4__principal_identity_map.sql}): the cloud
 * principal {@link #getPrincipalKey()} seen in {@link #getProvider()}'s audit logs is the DLAP
 * subject {@link #getSubjectId()} the PDP evaluates.
 *
 * <p>The key is unique per provider scope. Times are set by the writer from its injected clock.
 */
@Entity
@Table(name = "principal_identity_map",
        uniqueConstraints = @UniqueConstraint(name = "uq_principal_identity_map_key",
                columnNames = {"provider", "principal_key"}))
public class PrincipalIdentityMapping {

    /** Where an entry came from. */
    public enum Source {
        /** Written by {@link IdentityMapSeeder} from the configured seed file, which owns it. */
        SEED,
        /** Written by an operator with SQL; the seeder leaves it alone unless the file names its key. */
        MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private IdentityProviderScope provider;

    @Column(name = "principal_key", nullable = false, length = PrincipalKeys.MAX_LENGTH)
    private String principalKey;

    @Column(name = "subject_id", nullable = false, length = 512)
    private String subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The row id.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * Sets the row id.
     *
     * @param id the id
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * The provider scope the entry applies to.
     *
     * @return the scope
     */
    public IdentityProviderScope getProvider() {
        return provider;
    }

    /**
     * Sets the provider scope.
     *
     * @param provider the scope
     */
    public void setProvider(IdentityProviderScope provider) {
        this.provider = provider;
    }

    /**
     * The principal in {@link PrincipalKeys#key} form.
     *
     * @return the key
     */
    public String getPrincipalKey() {
        return principalKey;
    }

    /**
     * Sets the principal key.
     *
     * @param principalKey the key, already in {@link PrincipalKeys#key} form
     */
    public void setPrincipalKey(String principalKey) {
        this.principalKey = principalKey;
    }

    /**
     * The DLAP subject (AuthZEN {@code subject.id}) the principal is.
     *
     * @return the subject
     */
    public String getSubjectId() {
        return subjectId;
    }

    /**
     * Sets the DLAP subject.
     *
     * @param subjectId the subject
     */
    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    /**
     * Where the entry came from.
     *
     * @return the source
     */
    public Source getSource() {
        return source;
    }

    /**
     * Sets where the entry came from.
     *
     * @param source the source
     */
    public void setSource(Source source) {
        this.source = source;
    }

    /**
     * When the entry was first written.
     *
     * @return the time
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets when the entry was first written.
     *
     * @param createdAt the time
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * When the entry was last written.
     *
     * @return the time
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Sets when the entry was last written.
     *
     * @param updatedAt the time
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
