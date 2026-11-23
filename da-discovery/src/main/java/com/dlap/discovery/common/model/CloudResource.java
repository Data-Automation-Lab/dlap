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

package com.dlap.discovery.common.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes; // Hibernate's own SqlTypes

import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.aws.AwsResource;
import com.dlap.discovery.crawler.model.azure.AzureResource;
import com.dlap.discovery.crawler.model.gcp.GcpResource;
import com.dlap.discovery.crawler.model.oracle.OracleResource;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// JPA imports
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Base class representing a cloud resource, serving as the root of the
 * resource hierarchy.
 * This is a JPA entity potentially mapped using a single table inheritance
 * strategy.
 */
@Entity
@Table(name = "cloud_resource")
// Using single table inheritance, discriminator value usually comes from
// subclass annotations
@Inheritance(strategy = InheritanceType.JOINED)
// Align discriminator with Jackson's property if possible, otherwise define
// explicitly
// @DiscriminatorColumn(name = "dtype", discriminatorType =
// DiscriminatorType.STRING) Commented out
// Jackson annotations for polymorphism during serialization/deserialization
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "cloudProvider", // Using
                                                                                                          // cloudProvider
                                                                                                          // field for
                                                                                                          // type info
        visible = true // Make the type property readable
)
@JsonSubTypes({
        // Add all concrete subclasses here that might be serialized/deserialized
        // Use the value of the CloudProvider enum as the type name
        @JsonSubTypes.Type(value = GcpResource.class, name = "GCP"),
        @JsonSubTypes.Type(value = AwsResource.class, name = "AWS"),
        @JsonSubTypes.Type(value = AzureResource.class, name = "AZURE"),
        @JsonSubTypes.Type(value = OracleResource.class, name = "OCI"),
        @JsonSubTypes.Type(value = com.dlap.discovery.crawler.model.unity.UnityCatalogResource.class, name = "UNITY"),
        @JsonSubTypes.Type(value = com.dlap.discovery.crawler.model.databricks.DatabricksProcessingJobResource.class, name = "DATABRICKS")
// If specific types like EC2Resource are directly serialized, add them too:
// @JsonSubTypes.Type(value = EC2Resource.class, name = "AWS_EC2_INSTANCE"),
// @JsonSubTypes.Type(value = GcsResource.class, name = "GCP_GCS_BUCKET"),
// @JsonSubTypes.Type(value = AzureVMResource.class, name = "AZURE_VM")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * @deprecated Retired in v0.2.0 per ADR-0022 in favor of {@code Asset}
 * in {@code dlap_common} as the single source of truth. The auto-flow
 * critical path (crawler → catalog → label → policy → archive/delete)
 * no longer reads or writes this table after ADR-0021's P0-A through
 * P0-N landed; crawlers publish to {@code dlap.discovery.asset.discovered}
 * which {@code da-catalog} ingests into {@code Asset}. The legacy
 * {@code ResourceEventListener} + {@code CatalogResourceEventProcessor}
 * chain still writes here for service-internal queries, but those
 * endpoints migrate to {@code /api/v1/catalog/assets} in v0.2.0.
 * Do not add new references; new code targets {@code Asset}.
 */
@Deprecated(forRemoval = true, since = "0.1.0")
public abstract class CloudResource {

    /**
     * Internal database primary key (UUID).
     */
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Provider-specific unique identifier for the resource (e.g., ARN, Instance
     * ID).
     * Should be unique within the context of provider/type/account/region.
     */
    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    /**
     * URI identifying the resource, often provider-specific.
     */
    @Column(name = "uri")
    private String uri;

    /**
     * User-friendly or provider-assigned name of the resource.
     */
    @Column(name = "name")
    private String name;

    // --- ResourceType Components ---
    // We map components of the ResourceType record instead of embedding the record
    // directly
    // The ResourceType field itself is transient.

    @Transient // Actual ResourceType is built from these fields
    private ResourceType resourceType;

    /** Provider identifier (e.g., GCP, AWS, AZURE). Part of ResourceType. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cloud_provider", nullable = false)
    private CloudProvider cloudProvider;

    /** Service identifier (e.g., compute, storage, sql). Part of ResourceType. */
    @Column(name = "service_id", nullable = false)
    private String serviceId;

    /**
     * Specific type identifier within the service (e.g., instance, bucket,
     * database). Part of ResourceType.
     */
    @Column(name = "type_id", nullable = false)
    private String typeId;

    // --- End ResourceType Components ---

    /**
     * Cloud region where the resource is located (e.g., us-west1).
     */
    @Column(name = "region")
    private String region;

    /**
     * Cloud availability zone within a region (e.g., us-west1-a).
     */
    @Column(name = "zone")
    private String zone;

    /**
     * Identifier for the project (GCP specific concept).
     */
    @Column(name = "project_id")
    private String projectId;

    /**
     * Identifier for the cloud account or subscription (AWS Account ID, Azure
     * Subscription ID, OCI Tenancy OCID).
     */
    @Column(name = "account_id")
    private String accountId;

    /**
     * Timestamp when the resource was created in the cloud provider.
     */
    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * Timestamp when the resource was last updated in the cloud provider.
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Timestamp when the resource was last discovered or synchronized by our
     * system.
     */
    @Column(name = "last_discovered_at", nullable = false)
    private Instant lastDiscoveredAt;

    /**
     * Key-value tags associated with the resource in the cloud provider.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cloud_resource_tags", joinColumns = @JoinColumn(name = "resource_db_id"))
    @MapKeyColumn(name = "tag_key", length = 255) // Adjust length as needed
    @Column(name = "tag_value", length = 1024) // Adjust length as needed
    private Map<String, String> tags = new HashMap<>();

    /**
     * Raw JSON payload from the discovery source.
     * Hibernate 6.4+ should map this to JSONB with PostgreSQL dialect
     * when @JdbcTypeCode(SqlTypes.JSON) is used.
     */
    @JdbcTypeCode(SqlTypes.JSON) // Using Hibernate's SqlTypes.JSON
    @Column(name = "raw_json") // Liquibase should define this column as jsonb
    private String json;

    /**
     * General description of the resource.
     */
    @Column(name = "description", length = 2048) // Adjust length
    private String description;

    /**
     * Provider-specific ID of a parent resource in a hierarchy.
     */
    @Column(name = "parent_id")
    private String parentId;

    /**
     * General location information, potentially overlapping with region/zone.
     */
    @Column(name = "location")
    private String location;

    /**
     * Flag indicating if this resource might have child resources in a hierarchy.
     */
    @Column(name = "has_children")
    private boolean hasChildren;

    // --- New Fields ---

    /** User-defined technical metadata (e.g., env:prod, tier:backend). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cloud_resource_technical_metadata", joinColumns = @JoinColumn(name = "resource_db_id"))
    @MapKeyColumn(name = "metadata_key", length = 255)
    @Column(name = "metadata_value", length = 1024)
    private Map<String, String> technicalMetadata = new HashMap<>();

    /** User-defined business metadata (e.g., owner:team-a, cost-center:12345). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cloud_resource_business_metadata", joinColumns = @JoinColumn(name = "resource_db_id"))
    @MapKeyColumn(name = "metadata_key", length = 255)
    @Column(name = "metadata_value", length = 1024)
    private Map<String, String> businessMetadata = new HashMap<>();

    /** Applicable compliance standards (e.g., GDPR, HIPAA). */
    @ElementCollection(fetch = FetchType.LAZY, targetClass = ComplianceType.class)
    @CollectionTable(name = "cloud_resource_compliance", joinColumns = @JoinColumn(name = "resource_db_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_type", nullable = false)
    private Set<ComplianceType> compliance = new HashSet<>();

    /** User-defined labels or categories applied to the resource. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cloud_resource_labels", joinColumns = @JoinColumn(name = "resource_db_id"))
    @Column(name = "label", nullable = false)
    private List<String> labels = new ArrayList<>();

    /** Teams or groups subscribed to or interested in this resource. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cloud_resource_subscriber_teams", joinColumns = @JoinColumn(name = "resource_db_id"))
    @Column(name = "team_id", nullable = false)
    private List<String> subscriberTeams = new ArrayList<>();

    /** Historical lineage records associated with this resource. */
    @OneToMany(mappedBy = "cloudResource", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Lineage> lineages = new ArrayList<>(); // Changed from lineageId

    /** Usage statistics associated with this resource. */
    @OneToOne(mappedBy = "cloudResource", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private CloudResourceUsageStats usageStats; // Changed from usageStatsId

    // --- Transient / Derived Fields ---
    /**
     * Provider-specific properties or metadata. Marked Transient as persistence
     * strategy needs definition
     * (e.g., JSON serialization via @Convert).
     */
    @Transient
    private Map<String, Object> properties = new HashMap<>(); // Renamed from metadata for clarity

    // --- Constructor ---
    // Default constructor required by JPA
    protected CloudResource() {
    }

    // Constructor for minimal creation (Subclasses should call this)
    protected CloudResource(ResourceType resourceType, String resourceId, String name) {
        setResourceTypeInternal(Objects.requireNonNull(resourceType, "ResourceType cannot be null"));
        this.resourceId = Objects.requireNonNull(resourceId, "ResourceId cannot be null");
        this.name = name;
        this.lastDiscoveredAt = Instant.now();
    }

    // Helper to set ResourceType components
    private void setResourceTypeInternal(ResourceType resourceType) {
        this.resourceType = resourceType;
        if (resourceType != null) {
            if (resourceType.service() != null) {
                this.serviceId = resourceType.service().id();
                if (resourceType.service().provider() != null) {
                    this.cloudProvider = resourceType.service().provider();
                } else {
                    this.cloudProvider = null; // Or handle error/default
                }
            } else {
                this.serviceId = null; // Or handle error/default
                this.cloudProvider = null;
            }
            this.typeId = resourceType.id();
        } else {
            this.cloudProvider = null;
            this.serviceId = null;
            this.typeId = null;
        }
    }

    // --- Getters and Setters ---

    public UUID getId() {
        return id;
    }

    // No setId for generated primary key

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // Getter for ResourceType record (builds from components)
    @Transient // Exclude from persistence, calculated
    public ResourceType getResourceType() {
        // Return the transient field directly. It should be set via setResourceType.
        // Reconstruction from components is removed for simplicity and to avoid
        // ResourceService dependency.
        // If the object is loaded from DB, this transient field will likely be null
        // unless set explicitly post-load.
        // Rely on getCloudProviderEnum(), getServiceId(), getTypeId() for persisted
        // values.
        return this.resourceType;
    }

    // Setter for ResourceType record (updates components)
    public void setResourceType(ResourceType resourceType) {
        setResourceTypeInternal(Objects.requireNonNull(resourceType, "ResourceType cannot be null"));
    }

    // Getter for CloudProvider (Enum) - Direct from mapped field
    public CloudProvider getCloudProviderEnum() {
        return cloudProvider;
    }

    // Getter for CloudProvider Name (String) - Derived from enum
    @Transient // Derived, not persisted directly here
    public String getCloudProvider() {
        return cloudProvider != null ? cloudProvider.name() : null;
    }

    // Getter/Setter for Service ID (String)
    public String getServiceId() {
        return serviceId;
    }

    // Getter/Setter for Type ID (String)
    public String getTypeId() {
        return typeId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastDiscoveredAt() {
        return lastDiscoveredAt;
    }

    public void setLastDiscoveredAt(Instant lastDiscoveredAt) {
        this.lastDiscoveredAt = lastDiscoveredAt;
    }

    // For collections managed by JPA, returning the direct reference is often
    // standard.
    // Defensive copies primarily in setters or non-JPA contexts.
    public Map<String, String> getTags() {
        return tags; // Return direct reference for JPA
    }

    public void setTags(Map<String, String> tags) {
        this.tags.clear();
        if (tags != null) {
            this.tags.putAll(tags); // Replace content
        }
    }

    /**
     * Adds a single tag to the resource.
     * 
     * @param key   The tag key
     * @param value The tag value
     * @return The previous value associated with the key, or null if there was no
     *         mapping
     */
    public String addTag(String key, String value) {
        if (key == null) {
            throw new IllegalArgumentException("Tag key cannot be null");
        }
        return this.tags.put(key, value);
    }

    // Properties remains Transient for now
    @Transient
    public Map<String, Object> getProperties() {
        return properties == null ? Collections.emptyMap() : new HashMap<>(properties);
    }

    @Transient
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new HashMap<>() : new HashMap<>(properties);
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isHasChildren() {
        return hasChildren;
    }

    public void setHasChildren(boolean hasChildren) {
        this.hasChildren = hasChildren;
    }

    // --- New Getters and Setters ---

    public Map<String, String> getTechnicalMetadata() {
        return technicalMetadata; // Direct reference for JPA
    }

    public void setTechnicalMetadata(Map<String, String> technicalMetadata) {
        this.technicalMetadata.clear();
        if (technicalMetadata != null) {
            this.technicalMetadata.putAll(technicalMetadata);
        }
    }

    public Map<String, String> getBusinessMetadata() {
        return businessMetadata; // Direct reference for JPA
    }

    public void setBusinessMetadata(Map<String, String> businessMetadata) {
        this.businessMetadata.clear();
        if (businessMetadata != null) {
            this.businessMetadata.putAll(businessMetadata);
        }
    }

    public Set<ComplianceType> getCompliance() {
        return compliance; // Direct reference for JPA
    }

    public void setCompliance(Set<ComplianceType> compliance) {
        this.compliance.clear();
        if (compliance != null) {
            this.compliance.addAll(compliance);
        }
    }

    public List<String> getLabels() {
        return labels; // Direct reference for JPA
    }

    public void setLabels(List<String> labels) {
        this.labels.clear();
        if (labels != null) {
            this.labels.addAll(labels);
        }
    }

    public List<String> getSubscriberTeams() {
        return subscriberTeams; // Direct reference for JPA
    }

    public void setSubscriberTeams(List<String> subscriberTeams) {
        this.subscriberTeams.clear();
        if (subscriberTeams != null) {
            this.subscriberTeams.addAll(subscriberTeams);
        }
    }

    // Getters/Setters for relationships
    public List<Lineage> getLineages() {
        return lineages; // Direct reference
    }

    public void setLineages(List<Lineage> lineages) {
        // Manage relationship bidirectionality if needed
        this.lineages.clear();
        if (lineages != null) {
            this.lineages.addAll(lineages);
            // Set the owning side
            // lineages.forEach(l -> l.setCloudResource(this)); // Requires setter in
            // Lineage
        }
    }

    // Helper method to add a single lineage entry correctly
    public void addLineage(Lineage lineage) {
        if (lineage != null) {
            this.lineages.add(lineage);
            lineage.setCloudResource(this); // Set owning side
        }
    }

    public CloudResourceUsageStats getUsageStats() {
        return usageStats;
    }

    public void setUsageStats(CloudResourceUsageStats usageStats) {
        // Manage relationship bidirectionality
        if (usageStats == null) {
            if (this.usageStats != null) {
                this.usageStats.setCloudResource(null);
            }
        } else {
            usageStats.setCloudResource(this);
        }
        this.usageStats = usageStats;
    }

    // --- Deprecated/Alias methods - Mark Transient ---

    // Renamed metadata field to properties, keep getMetadata for compatibility?
    // Mark transient as properties is transient
    @Transient
    @Deprecated
    public Map<String, Object> getMetadata() {
        return getProperties(); // Delegate to properties
    }

    @Transient
    @Deprecated
    public void setMetadata(Map<String, Object> metadata) {
        setProperties(metadata); // Delegate to properties
    }

    // Add metadata directly manipulates transient field
    @Transient
    public void addMetadata(String key, Object value) {
        if (this.properties == null) {
            this.properties = new HashMap<>();
        }
        this.properties.put(key, value);
    }

    /**
     * Gets the resource type record (alias for getResourceType()).
     */
    @Transient // Alias for transient getter
    @JsonIgnore
    public ResourceType getType() {
        return getResourceType();
    }

    /**
     * Sets the resource type record (alias for setResourceType()).
     */
    public void setType(ResourceType type) {
        setResourceType(type);
    }

    /**
     * Gets the cloud provider name (derived).
     */
    @Transient // Derived
    @JsonIgnore
    public String getProvider() {
        return getCloudProvider();
    }

    /**
     * Setting the provider directly is discouraged. Marked Transient.
     */
    @Transient
    @Deprecated
    public void setProvider(String provider) {
        // Intentionally do nothing or log a warning?
        System.err.println(
                "Warning: Setting provider string directly on CloudResource is discouraged. Use setResourceType.");
    }

    /**
     * Gets the last modified time (alias for getUpdatedAt()).
     */
    @Transient // Alias
    @JsonIgnore
    public Instant getLastModified() {
        return getUpdatedAt();
    }

    /**
     * Sets the last modified time (alias for setUpdatedAt()).
     */
    public void setLastModified(Instant lastModified) {
        setUpdatedAt(lastModified);
    }

    // Method for provider-specific type might be useful in subclasses
    @Transient // Implementation likely depends on transient ResourceType or specific fields
    @JsonIgnore
    public abstract String getProviderSpecificType();

    // --- Helper methods ---

    /**
     * Returns a unique identifier string combining provider, type, and resource ID.
     * Example: GCP:gcp_compute_instance:my-vm-123
     * Marked Transient as it's derived.
     */
    @Transient
    @JsonIgnore
    public String getUniqueIdString() {
        ResourceType rt = getResourceType(); // Use getter to ensure it's potentially reconstructed
        if (rt == null || getResourceId() == null || getCloudProvider() == null) {
            return null;
        }
        return String.format("%s:%s:%s",
                getCloudProvider(), // Use derived string getter
                rt.id(),
                getResourceId());
    }

    /**
     * Gets the raw resource representation (alias for getJson()). Marked Transient.
     */
    @Transient
    @Deprecated
    public String getRawResource() {
        return getJson();
    }

    // SelfLink might come from transient 'properties' or be specific logic
    @Transient
    public String getSelfLink() {
        // Update logic if 'properties' persistence changes
        return (String) getProperties().get("selfLink");
    }

    // --- equals, hashCode, toString ---

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        // Enhanced check for JPA proxies
        if (o == null || getClass() != o.getClass() || !(o instanceof CloudResource)) {
            // Consider Hibernate.unproxy(o).getClass() ==
            // Hibernate.unproxy(this).getClass()
            return false;
        }
        CloudResource that = (CloudResource) o;

        // Prefer business key equality (provider, type, resourceId, accountId?) over
        // generated ID
        // before persistence. After persistence, ID is reliable if not null.
        if (id != null && that.id != null) {
            return Objects.equals(id, that.id);
        }

        // Business Key: cloudProvider, typeId, resourceId, accountId
        return Objects.equals(cloudProvider, that.cloudProvider) &&
                Objects.equals(typeId, that.typeId) && // Use mapped typeId
                Objects.equals(resourceId, that.resourceId) &&
                Objects.equals(accountId, that.accountId); // Add accountId for uniqueness
    }

    @Override
    public int hashCode() {
        // Use business key for hashCode consistency before and after persistence
        // Business Key: cloudProvider, typeId, resourceId, accountId
        return Objects.hash(cloudProvider, typeId, resourceId, accountId);
    }

    @Override
    public String toString() {
        // Use mapped fields and avoid calling methods that might trigger lazy loading
        // if not needed
        return getClass().getSimpleName() + "{" + // Use getClass().getSimpleName() for subclass info
                "id=" + id +
                ", resourceId='" + resourceId + "\'" +
                ", cloudProvider=" + cloudProvider +
                ", serviceId='" + serviceId + "\'" +
                ", typeId='" + typeId + "\'" +
                ", name='" + name + "\'" +
                ", region='" + region + "\'" +
                ", accountId='" + accountId + "\'" +
                ", labels=" + (labels != null ? labels.size() : 0) + // Avoid loading full list
                ", compliance=" + (compliance != null ? compliance.size() : 0) + // Avoid loading full set
                ", lineages=" + (lineages != null ? lineages.size() : 0) + // Avoid loading full list
                ", hasUsageStats=" + (usageStats != null) +
                '}';
    }

    // --- Builder ---
    // Builder is deprecated and operates on transient fields/direct setters.
    // Needs significant rework or removal for JPA entity. Keeping as deprecated.

    /**
     * @deprecated Use specific subclass builders or factory methods instead.
     *             Builder may not work correctly with JPA lifecycle and
     *             relationships.
     */
    @Deprecated
    public static Builder builder() {
        throw new UnsupportedOperationException(
                "Use specific subclass builders (e.g., GCPResource.Builder) or factory methods. Generic builder is not suitable for JPA entities.");
    }

    /**
     * @deprecated Builder may not work correctly with JPA lifecycle and
     *             relationships.
     */
    @Deprecated
    public abstract static class Builder {
        // Builder logic remains largely unchanged but operates on potentially detached
        // instances
        // and doesn't handle relationships or JPA specifics well.
        protected final CloudResource resource; // Must be instantiated by subclass builder

        protected Builder(CloudResource instance) {
            this.resource = instance;
        }

        // Common builder methods still call direct setters...
        public Builder id(UUID id) {
            // resource.setId(id); // ID is generated, cannot be set manually typically
            System.err.println("Warning: Setting ID via builder is not recommended for JPA entities.");
            return this;
        }

        public Builder resourceId(String resourceId) {
            resource.setResourceId(resourceId);
            return this;
        }

        public Builder name(String name) {
            resource.setName(name);
            return this;
        }

        // ResourceType MUST be set by the subclass builder appropriately
        public Builder resourceType(ResourceType resourceType) {
            resource.setResourceType(resourceType); // Calls setter that updates components
            return this;
        }

        public Builder region(String region) {
            resource.setRegion(region);
            return this;
        }

        public Builder zone(String zone) {
            resource.setZone(zone);
            return this;
        }

        public Builder projectId(String projectId) {
            resource.setProjectId(projectId);
            return this;
        }

        public Builder accountId(String accountId) {
            resource.setAccountId(accountId);
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            resource.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            resource.setUpdatedAt(updatedAt);
            return this;
        }

        public Builder lastDiscoveredAt(Instant lastDiscoveredAt) {
            resource.setLastDiscoveredAt(lastDiscoveredAt);
            return this;
        }

        public Builder tags(Map<String, String> tags) {
            resource.setTags(tags);
            return this;
        }

        // Properties is transient, setting via builder affects only the object state
        public Builder properties(Map<String, Object> properties) {
            resource.setProperties(properties);
            return this;
        }

        public Builder description(String description) {
            resource.setDescription(description);
            return this;
        }

        public Builder parentId(String parentId) {
            resource.setParentId(parentId);
            return this;
        }

        public Builder location(String location) {
            resource.setLocation(location);
            return this;
        }

        // Metadata affects transient properties
        public Builder metadata(Map<String, Object> metadata) {
            resource.setMetadata(metadata);
            return this;
        }

        public Builder hasChildren(boolean hasChildren) {
            resource.setHasChildren(hasChildren);
            return this;
        }

        public Builder uri(String uri) {
            resource.setUri(uri);
            return this;
        }

        public Builder json(String json) {
            resource.setJson(json);
            return this;
        }

        // Add setters for new fields in Builder
        public Builder technicalMetadata(Map<String, String> technicalMetadata) {
            resource.setTechnicalMetadata(technicalMetadata);
            return this;
        }

        public Builder businessMetadata(Map<String, String> businessMetadata) {
            resource.setBusinessMetadata(businessMetadata);
            return this;
        }

        public Builder compliance(Set<ComplianceType> compliance) {
            resource.setCompliance(compliance);
            return this;
        }

        public Builder labels(List<String> labels) {
            resource.setLabels(labels);
            return this;
        }

        public Builder subscriberTeams(List<String> subscriberTeams) {
            resource.setSubscriberTeams(subscriberTeams);
            return this;
        }

        // Setting relationships via builder is complex with JPA
        public Builder lineages(List<Lineage> lineages) {
            resource.setLineages(lineages); // Doesn't guarantee relationship consistency
            System.err.println("Warning: Setting lineages via builder might break relationship consistency.");
            return this;
        }

        public Builder usageStats(CloudResourceUsageStats usageStats) {
            resource.setUsageStats(usageStats); // Doesn't guarantee relationship consistency
            System.err.println("Warning: Setting usageStats via builder might break relationship consistency.");
            return this;
        }

        // build() method validation needs update for mapped ResourceType components
        public CloudResource build() {
            // Basic validation based on mapped fields
            if (resource.getCloudProviderEnum() == null || resource.getServiceId() == null
                    || resource.getTypeId() == null) {
                throw new IllegalStateException(
                        "ResourceType components (provider, serviceId, typeId) must be set via ResourceType.");
            }
            if (resource.getResourceId() == null) {
                throw new IllegalStateException("ResourceId must be set.");
            }
            if (resource.getLastDiscoveredAt() == null) {
                // Consider setting default in constructor or field initializer
                resource.setLastDiscoveredAt(Instant.now());
            }
            // Further validation or relationship setup might be needed here,
            // but builders are less suitable for complex JPA entities.
            return resource;
        }
    }

    /**
     * Updates the updatedAt timestamp before any update operation.
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}