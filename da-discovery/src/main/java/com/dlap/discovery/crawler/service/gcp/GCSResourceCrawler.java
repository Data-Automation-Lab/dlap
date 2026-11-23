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

package com.dlap.discovery.crawler.service.gcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.dlap.common.assetid.AssetKind;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.exception.ExceptionUtils;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.dlap.discovery.crawler.service.CrawlerException;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.log.exception.CloudProviderException;
import com.google.auth.Credentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * Discovers GCP Storage buckets and objects asynchronously and publishes events to Kafka.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class GCSResourceCrawler extends GCPResourceCrawler<GcsResource> {

    private static final Logger log = LoggerFactory.getLogger(GCSResourceCrawler.class);
    private static final String BUCKET_RESOURCE_TYPE_ID = "gcp_gcs_bucket";
    private static final String OBJECT_RESOURCE_TYPE_ID = "gcp_gcs_object";

    // Configuration property for excluded buckets
    @Value("${gcp.bucket.exclude.objects}")
    private String excludedBuckets;

    private Set<String> excludedBucketSet = Collections.emptySet();

    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    /**
     * Constructor for GCSResourceCrawler.
     * Initializes the crawler with appropriate resource types and Kafka template.
     */
    @Autowired
    public GCSResourceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate) {
        super(new ArrayList<>());
        this.hierarchyRegistry = hierarchyRegistry;
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");

        // Resource types are set below in constructor
        ResourceType bucketRt = hierarchyRegistry.getResourceType(BUCKET_RESOURCE_TYPE_ID);
        if (bucketRt != null) {
            getSupportedResourceTypes().add(bucketRt);
            log.info("GCSResourceCrawler initialized for bucket type: {}", BUCKET_RESOURCE_TYPE_ID);
        } else {
            log.error("ResourceType record not found for ID: {}. Bucket discovery may not function.",
                    BUCKET_RESOURCE_TYPE_ID);
        }

        ResourceType objectRt = hierarchyRegistry.getResourceType(OBJECT_RESOURCE_TYPE_ID);
        if (objectRt != null) {
            getSupportedResourceTypes().add(objectRt);
            log.info("GCSResourceCrawler initialized for object type: {}", OBJECT_RESOURCE_TYPE_ID);
        } else {
            log.error("ResourceType record not found for ID: {}. Object discovery may not function.",
                    OBJECT_RESOURCE_TYPE_ID);
        }
    }

    /**
     * Initializes the excluded bucket set after dependency injection.
     */
    @PostConstruct
    private void initExcludedBuckets() {
        this.excludedBucketSet = (excludedBuckets != null && !excludedBuckets.isEmpty())
                ? Arrays.stream(excludedBuckets.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet())
                : Collections.emptySet();
        log.info("Excluded buckets for object discovery: {}", excludedBucketSet);
    }

    /**
     * Initiates asynchronous discovery of GCS buckets and objects.
     *
     * @param accountId The GCP project ID
     * @param context   The discovery context
     */
    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous GCS bucket and object discovery for project: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    /**
     * Performs the actual discovery of GCS buckets and objects asynchronously.
     *
     * @param projectId The GCP project ID
     * @param context   The discovery context
     */
    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String projectId, Map<String, Object> context) {
        log.info("Starting async GCS bucket and object discovery for project: {}", projectId);
        int bucketCount = 0;
        int objectCount = 0;

        try {
            Credentials credentials = getAuthService().getCredentials();
            log.debug("Using credentials to access GCS for project: {}", projectId);

            Storage storage = StorageOptions.newBuilder()
                    .setProjectId(projectId)
                    .setCredentials(credentials)
                    .build()
                    .getService();

            log.debug("Listing buckets for project: {}", projectId);
            List<Bucket> buckets = StreamSupport
                    .stream(storage.list().iterateAll().spliterator(), false)
                    .collect(Collectors.toList());

            log.info("Found {} buckets in project {}", buckets.size(), projectId);

            ResourceType bucketRt = findSupportedType(BUCKET_RESOURCE_TYPE_ID);
            if (bucketRt == null) {
                log.error("ResourceType record not found for ID: {}. Cannot create GcsResource for buckets.", 
                        BUCKET_RESOURCE_TYPE_ID);
                return;
            }

            ResourceType objectRt = findSupportedType(OBJECT_RESOURCE_TYPE_ID);
            if (objectRt == null) {
                log.error("ResourceType record not found for ID: {}. Cannot create GcsResource for objects.", 
                        OBJECT_RESOURCE_TYPE_ID);
                // Continue with bucket discovery even if object type is missing
            }

            for (Bucket bucket : buckets) {
                // Process bucket
                GcsResource bucketResource = convertBucketToResource(bucket, projectId, bucketRt);
                if (bucketResource != null) {
                    publishResourceEvent(projectId, bucketResource, ChangeType.CREATE);
                    bucketCount++;
                    log.debug("Published event for GCS bucket: {}", bucket.getName());
                }

                // Process objects if object resource type is available and bucket is not excluded
                if (objectRt != null && !excludedBucketSet.contains(bucket.getName())) {
                    log.debug("Listing objects for bucket: {}", bucket.getName());
                    List<Blob> objects = StreamSupport
                            .stream(bucket.list().iterateAll().spliterator(), false)
                            .collect(Collectors.toList());

                    for (Blob blob : objects) {
                        // No object-skipping logic; process all objects
                        GcsResource objectResource = convertObjectToResource(blob, bucket, projectId, objectRt);
                        if (objectResource != null) {
                            publishResourceEvent(projectId, objectResource, ChangeType.CREATE);
                            objectCount++;
                            log.debug("Published event for GCS object: {}", blob.getName());
                        }
                    }
                } else if (objectRt != null) {
                    log.debug("Skipping object discovery for excluded bucket: {}", bucket.getName());
                }
            }

            log.info("Completed async GCS discovery for project: {}, published {} buckets and {} objects",
                    projectId, bucketCount, objectCount);

        } catch (Exception e) {
            log.error("Error during async GCS discovery for project {}: {}",
                    projectId, e.getMessage(), e);
            if (e instanceof CloudProviderException || e instanceof CrawlerException) {
                throw (RuntimeException) e;
            } else {
                throw ExceptionUtils.crawlerExecutionFailed("GCSResourceCrawler", null, "listBucketsAndObjects", e);
            }
        }
    }

    /**
     * Helper method to publish resource events to Kafka.
     */
    private void publishResourceEvent(String accountId, GcsResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.GCP.toString(),
                    accountId,
                    changeType,
                    resource);
            log.debug("Publishing resource event: {}", event);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for GCS Resource {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }

    /**
     * Converts a Google Storage Bucket to a GcsResource.
     *
     * @param bucket    The Google Storage Bucket
     * @param projectId The GCP project ID
     * @param rt        The ResourceType record for GCS buckets
     * @return A GcsResource representing the bucket
     */
    GcsResource convertBucketToResource(Bucket bucket, String projectId, ResourceType rt) {
        GcsResource resource = new GcsResource(rt, bucket.getName(), bucket.getName());

        resource.setAccountId(projectId);
        resource.setProjectId(projectId);
        // Canonical id (ADR-0047): gs://{bucket}. The project is an attribute, not identity.
        CrawlerAssetIds.assignBuilt(resource, AssetKind.GCS_BUCKET, bucket.getName());
        resource.setLocation(bucket.getLocation());
        resource.setLastDiscoveredAt(Instant.now());
        if (bucket.getCreateTimeOffsetDateTime() != null) {
            resource.setCreatedAt(bucket.getCreateTimeOffsetDateTime().toInstant());
        } else if (bucket.getCreateTime() != null) {
            resource.setCreatedAt(Instant.ofEpochMilli(bucket.getCreateTime()));
        }
        if (bucket.getUpdateTimeOffsetDateTime() != null) {
            resource.setUpdatedAt(bucket.getUpdateTimeOffsetDateTime().toInstant());
        } else if (bucket.getUpdateTime() != null) {
            resource.setUpdatedAt(Instant.ofEpochMilli(bucket.getUpdateTime()));
        } else {
            resource.setUpdatedAt(Instant.now());
        }
        if (bucket.getStorageClass() != null) {
            resource.setStorageClass(bucket.getStorageClass().toString());
        }
        boolean isPublic = bucket.getAcl() != null &&
                bucket.getAcl().stream().anyMatch(acl -> acl.getEntity().toString().contains("allUsers") ||
                        acl.getEntity().toString().contains("allAuthenticatedUsers"));
        resource.setPublic(isPublic);
        resource.setSizeBytes(-1L);
        if (bucket.getLabels() != null) {
            resource.setTags(bucket.getLabels());
        }
        Boolean versioningEnabled = bucket.versioningEnabled();
        resource.addMetadata("versioningEnabled", versioningEnabled != null ? versioningEnabled.toString() : "false");
        Boolean requesterPays = bucket.requesterPays();
        resource.addMetadata("requesterPays", requesterPays != null ? requesterPays.toString() : "false");
        resource.addMetadata("locationType", bucket.getLocationType());

        return resource;
    }

    /**
     * Converts a Google Storage Blob (object) to a GcsResource.
     *
     * @param blob      The Google Storage Blob
     * @param bucket    The parent bucket
     * @param projectId The GCP project ID
     * @param rt        The ResourceType record for GCS objects
     * @return A GcsResource representing the object
     */
    GcsResource convertObjectToResource(Blob blob, Bucket bucket, String projectId, ResourceType rt) {
        GcsResource resource = new GcsResource(rt, blob.getName(), "gs://" + bucket.getName() + "/" + blob.getName());

        resource.setAccountId(projectId);
        resource.setProjectId(projectId);
        // Canonical id (ADR-0047): gs://{bucket}/{object}. The resource id is the bare object name,
        // which collides across buckets, so it is never used as identity.
        CrawlerAssetIds.assignBuilt(resource, AssetKind.GCS_OBJECT, bucket.getName(), blob.getName());
        resource.setLocation(bucket.getLocation()); // Use bucket's location
        resource.setLastDiscoveredAt(Instant.now());
        if (blob.getCreateTimeOffsetDateTime() != null) {
            resource.setCreatedAt(blob.getCreateTimeOffsetDateTime().toInstant());
        } else if (blob.getCreateTime() != null) {
            resource.setCreatedAt(Instant.ofEpochMilli(blob.getCreateTime()));
        }
        if (blob.getUpdateTimeOffsetDateTime() != null) {
            resource.setUpdatedAt(blob.getUpdateTimeOffsetDateTime().toInstant());
        } else if (blob.getUpdateTime() != null) {
            resource.setUpdatedAt(Instant.ofEpochMilli(blob.getUpdateTime()));
        } else {
            resource.setUpdatedAt(Instant.now());
        }
        resource.setSizeBytes(blob.getSize() != null ? blob.getSize() : -1L);
        // The observed object size travels as technical metadata; the bridge forwards it as size_bytes.
        CrawlerAssetIds.recordSize(resource, blob.getSize());
        boolean isPublic = blob.getAcl() != null &&
                blob.getAcl().stream().anyMatch(acl -> acl.getEntity().toString().contains("allUsers") ||
                        acl.getEntity().toString().contains("allAuthenticatedUsers"));
        resource.setPublic(isPublic);

        // Set content type and storage class with defaults
        String contentType = blob.getContentType();
        if (contentType == null || contentType.isEmpty()) {
            // Try to determine content type from file extension
            String name = blob.getName().toLowerCase();
            if (name.endsWith(".json")) {
                contentType = "application/json";
            } else if (name.endsWith(".csv")) {
                contentType = "text/csv";
            } else if (name.endsWith(".txt")) {
                contentType = "text/plain";
            } else if (name.endsWith(".docx")) {
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else {
                contentType = "application/octet-stream";
            }
        }
        resource.setContentType(contentType);

        // Set storage class with default
        String storageClass = blob.getStorageClass() != null ? blob.getStorageClass().toString() : "STANDARD";
        resource.setStorageClass(storageClass);

        // Set tags with defaults
        Map<String, String> tags = new HashMap<>();
        if (blob.getMetadata() != null) {
            tags.putAll(blob.getMetadata());
        }
        // Add default tags
        tags.put("bucket", bucket.getName());
        tags.put("project", projectId);
        tags.put("storageClass", storageClass);
        tags.put("contentType", contentType);
        resource.setTags(tags);

        resource.addMetadata("bucketName", bucket.getName());
        resource.addMetadata("contentType", contentType);
        resource.addMetadata("storageClass", storageClass);

        return resource;
    }

    /**
     * Helper to find the ResourceType record based on ID.
     */
    private ResourceType findSupportedType(String typeId) {
        for (ResourceType type : getSupportedResourceTypes()) {
            if (typeId.equals(type.id())) {
                return type;
            }
        }
        log.warn("ResourceType not found in supported types: {}", typeId);
        return null;
    }
}