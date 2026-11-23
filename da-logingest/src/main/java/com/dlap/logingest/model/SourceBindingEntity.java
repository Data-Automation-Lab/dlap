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

package com.dlap.logingest.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The database mirror of one binding ({@code dlap_logingest.source_binding}, Flyway V3, design B2
 * "Where the config lives"). The bindings file is authoritative; the synchronizer writes this row
 * at startup with the binding's effective configuration and its SHA-256 hash, so cursors,
 * observations, quarantine records and backfill jobs can reference the binding by id.
 */
@Entity
@Table(name = "source_binding")
@Getter
@Setter
@NoArgsConstructor
public class SourceBindingEntity {

    /** The binding id. */
    @Id
    @Column(name = "binding_id", length = 128)
    private String bindingId;

    /** The id of the source the binding belongs to. */
    @Column(name = "source_id", length = 128, nullable = false)
    private String sourceId;

    /** The source kind, upper snake case. */
    @Column(name = "source_kind", length = 64, nullable = false)
    private String sourceKind;

    /** Where the row comes from. */
    @Enumerated(EnumType.STRING)
    @Column(name = "config_origin", length = 16, nullable = false)
    private ConfigOrigin configOrigin;

    /** The binding's state at the last synchronization. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private BindingState state;

    /** Whether the configuration enables the binding. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** The binding's scope (account, project, subscription, workspace, regions). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> scope;

    /** The binding's effective configuration, as hashed. Never holds a secret value. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "effective_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> effectiveConfig;

    /** SHA-256 of the binding's effective configuration. */
    @Column(name = "config_hash", length = 64, nullable = false)
    private String configHash;

    /** SHA-256 of the whole effective configuration at the last synchronization. */
    @Column(name = "deployment_config_hash", length = 64)
    private String deploymentConfigHash;

    /** When the binding was first synchronized. */
    @Column(name = "first_synced_at", nullable = false)
    private Instant firstSyncedAt;

    /** When the binding was last synchronized. */
    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    /** When the binding left the configuration, or {@code null}. */
    @Column(name = "removed_at")
    private Instant removedAt;
}
