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
import java.util.List;
import java.util.Map;
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
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DLAP CloudResource Entity (ADR-0028).
 *
 * <p>A richer, <b>optional</b> shared model that complements {@link Asset}.
 * {@code Asset} remains the lightweight canonical cross-service asset (the
 * single source of truth on the auto-flow path per ADR-0021). A
 * {@code CloudResource} carries provider-native detail that does not belong
 * on {@code Asset}:
 *
 * <ul>
 *   <li><b>Hierarchy / asset-of-assets</b> - {@link #parentResourceId} +
 *       {@link #hasChildren} model parent/child trees (e.g. a BigQuery
 *       dataset containing tables, an S3 bucket containing prefixes).</li>
 *   <li><b>Cross-region replication</b> - {@link #replicaRegions} records
 *       the regions a resource is replicated to beyond its primary
 *       {@link #region}.</li>
 *   <li><b>Large-asset / provider-native metadata</b>:
 *       {@link #providerNativeJson} holds the raw provider payload that is
 *       too large or too provider-specific to flatten onto {@code Asset}.</li>
 * </ul>
 *
 * <p>{@link #assetId} optionally links this resource to its canonical
 * {@code Asset}. Adoption is opt-in; see {@code docs/specs/cloud-resource.md}
 * for the migration guidance. This shared type is the convergence target for
 * {@code da-discovery}'s internal {@code CloudResource} (ADR-0028).
 *
 * @author DLAP Team
 * @since 0.2.0
 */
@Entity
@Table(name = "dlap_cloud_resources", schema = "dlap_common",
       indexes = {
           @Index(name = "idx_cloud_resource_native_id", columnList = "nativeResourceId"),
           @Index(name = "idx_cloud_resource_cloud_provider", columnList = "cloudProvider"),
           @Index(name = "idx_cloud_resource_asset", columnList = "assetId"),
           @Index(name = "idx_cloud_resource_parent", columnList = "parentResourceId"),
           @Index(name = "idx_cloud_resource_account", columnList = "accountId")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloudResource {

    /** Unique DLAP identifier for the cloud resource. */
    @Id
    @Column(name = "cloud_resource_id", columnDefinition = "UUID")
    private UUID cloudResourceId;

    /**
     * Optional link to the canonical {@link Asset} this resource maps to.
     * Null when a resource has not (yet) been promoted to an Asset.
     */
    @Column(name = "asset_id", columnDefinition = "UUID")
    private UUID assetId;

    /** Cloud provider (e.g. {@code AWS}, {@code GCP}, {@code AZURE}, {@code OCI}). */
    @NotBlank
    @Column(name = "cloud_provider", length = 20, nullable = false)
    private String cloudProvider;

    /** Provider service (e.g. {@code s3}, {@code bigquery}, {@code rds}). */
    @Column(name = "service_id", length = 64)
    private String serviceId;

    /** Provider resource type within the service (e.g. {@code s3_bucket}). */
    @Column(name = "resource_type", length = 100)
    private String resourceType;

    /** Provider-native resource identifier (ARN, self-link, OCID, …). */
    @Column(name = "native_resource_id", length = 1024)
    private String nativeResourceId;

    /** Canonical URI for the resource. */
    @Column(name = "resource_uri", length = 2048)
    private String resourceUri;

    /** Human-readable name. */
    @Column(name = "name", length = 512)
    private String name;

    /** Free-text description. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Cloud account / subscription / project owning the resource. */
    @Column(name = "account_id", length = 255)
    private String accountId;

    /** Provider project id (GCP project, …), where applicable. */
    @Column(name = "project_id", length = 255)
    private String projectId;

    /** Primary region. */
    @Column(name = "region", length = 64)
    private String region;

    /** Availability zone, where applicable. */
    @Column(name = "zone", length = 64)
    private String zone;

    /** Free-form provider location string when region/zone don't apply. */
    @Column(name = "location", length = 255)
    private String location;

    // ---- Richer-than-Asset detail (ADR-0028) ----

    /** Parent resource (asset-of-assets hierarchy); null for roots. */
    @Column(name = "parent_resource_id", columnDefinition = "UUID")
    private UUID parentResourceId;

    /** Whether this resource has child resources. */
    @Column(name = "has_children")
    private Boolean hasChildren;

    /** Regions this resource is replicated to (cross-region replication). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "replica_regions", columnDefinition = "jsonb")
    private List<String> replicaRegions;

    /** Provider tags. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private Map<String, String> tags;

    /**
     * Raw provider-native payload - large-asset metadata that does not
     * flatten onto {@link Asset}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_native_json", columnDefinition = "jsonb")
    private Map<String, Object> providerNativeJson;

    // ---- Lifecycle timestamps ----

    @Column(name = "first_discovered_ts", updatable = false)
    private Instant firstDiscoveredTs;

    @Column(name = "last_discovered_ts")
    private Instant lastDiscoveredTs;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "last_modified_ts", nullable = false)
    private Instant lastModifiedTs;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (cloudResourceId == null) {
            cloudResourceId = UUID.randomUUID();
        }
        if (createdTs == null) {
            createdTs = now;
        }
        if (firstDiscoveredTs == null) {
            firstDiscoveredTs = now;
        }
        lastModifiedTs = now;
    }

    @PreUpdate
    void onUpdate() {
        lastModifiedTs = Instant.now();
    }
}
