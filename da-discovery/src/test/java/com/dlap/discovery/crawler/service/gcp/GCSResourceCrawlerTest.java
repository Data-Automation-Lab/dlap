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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.StorageClass;

/**
 * First tests of {@link GCSResourceCrawler}: how a bucket and an object are mapped to a
 * {@link GcsResource} with a canonical id, a creation time and an observed size (ADR-0046,
 * ADR-0047). The Storage client is not exercised (it needs credentials and a network); the SDK
 * objects that carry the data are mocked, the same seam the RDS crawler test uses.
 */
class GCSResourceCrawlerTest {

    private static final String PROJECT = "dlap-demo";
    private static final CloudService GCS = new CloudService("gcs", "Google Cloud Storage", CloudProvider.GCP);
    private static final ResourceType BUCKET_TYPE = new ResourceType("gcp_gcs_bucket", "GCS Bucket", GCS);
    private static final ResourceType OBJECT_TYPE = new ResourceType("gcp_gcs_object", "GCS Object", GCS);

    private GCSResourceCrawler crawler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType("gcp_gcs_bucket")).thenReturn(BUCKET_TYPE);
        when(registry.getResourceType("gcp_gcs_object")).thenReturn(OBJECT_TYPE);
        crawler = new GCSResourceCrawler(registry, mock(KafkaTemplate.class));
    }

    private static Bucket bucket(String name) {
        Bucket bucket = mock(Bucket.class);
        when(bucket.getName()).thenReturn(name);
        when(bucket.getLocation()).thenReturn("US");
        when(bucket.getCreateTimeOffsetDateTime())
                .thenReturn(OffsetDateTime.of(2025, 11, 3, 8, 30, 0, 0, ZoneOffset.UTC));
        when(bucket.getStorageClass()).thenReturn(StorageClass.STANDARD);
        when(bucket.getLabels()).thenReturn(Map.of("team", "data-eng"));
        when(bucket.versioningEnabled()).thenReturn(true);
        return bucket;
    }

    private static Blob blob(String name, Long size) {
        Blob blob = mock(Blob.class);
        when(blob.getName()).thenReturn(name);
        when(blob.getSize()).thenReturn(size);
        when(blob.getCreateTimeOffsetDateTime())
                .thenReturn(OffsetDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC));
        return blob;
    }

    @Test
    void registersTheBucketAndObjectTypes() {
        assertThat(crawler.getSupportedResourceTypes()).containsExactly(BUCKET_TYPE, OBJECT_TYPE);
    }

    @Test
    void aBucketGetsTheCanonicalUriAndItsCreationTime() {
        GcsResource resource = crawler.convertBucketToResource(bucket("demo-bucket"), PROJECT, BUCKET_TYPE);

        assertThat(resource.getUri()).isEqualTo("gs://demo-bucket");
        assertThat(resource.getResourceId()).isEqualTo("demo-bucket");
        assertThat(resource.getCloudProviderEnum()).isEqualTo(CloudProvider.GCP);
        assertThat(resource.getProjectId()).isEqualTo(PROJECT);
        assertThat(resource.getAccountId()).isEqualTo(PROJECT);
        assertThat(resource.getLocation()).isEqualTo("US");
        assertThat(resource.getCreatedAt()).isEqualTo(Instant.parse("2025-11-03T08:30:00Z"));
        assertThat(resource.getTags()).containsEntry("team", "data-eng");
    }

    @Test
    void aBucketHasNoObservedSizeBecauseTheApiGivesNone() {
        GcsResource resource = crawler.convertBucketToResource(bucket("demo-bucket"), PROJECT, BUCKET_TYPE);

        // The legacy column keeps its -1 sentinel; nothing is forwarded as a size (unknown is not zero).
        assertThat(resource.getSizeBytes()).isEqualTo(-1L);
        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
    }

    @Test
    void anObjectGetsTheBucketQualifiedUriNotTheBareKey() {
        Bucket parent = bucket("demo-bucket");

        GcsResource resource = crawler.convertObjectToResource(
                blob("reports/2026/q3.csv", 2048L), parent, PROJECT, OBJECT_TYPE);

        assertThat(resource.getUri()).isEqualTo("gs://demo-bucket/reports/2026/q3.csv");
        // The resource id is the bare key, which collides across buckets: it is never the identity.
        assertThat(resource.getResourceId()).isEqualTo("reports/2026/q3.csv");
        assertThat(resource.getName()).isEqualTo("gs://demo-bucket/reports/2026/q3.csv");
        assertThat(resource.getCreatedAt()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
    }

    @Test
    void anObjectKeyIsKeptVerbatimInTheUri() {
        GcsResource resource = crawler.convertObjectToResource(
                blob("Reports/Q3 final%20v2.csv", 1L), bucket("demo-bucket"), PROJECT, OBJECT_TYPE);

        assertThat(resource.getUri()).isEqualTo("gs://demo-bucket/Reports/Q3 final%20v2.csv");
    }

    @Test
    void anObjectCarriesItsObservedSize() {
        GcsResource resource = crawler.convertObjectToResource(
                blob("data.parquet", 10_485_760L), bucket("demo-bucket"), PROJECT, OBJECT_TYPE);

        assertThat(resource.getSizeBytes()).isEqualTo(10_485_760L);
        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).contains(10_485_760L);
    }

    @Test
    void anEmptyObjectHasARealSizeOfZero() {
        GcsResource resource = crawler.convertObjectToResource(
                blob("_SUCCESS", 0L), bucket("demo-bucket"), PROJECT, OBJECT_TYPE);

        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).contains(0L);
    }

    @Test
    void anObjectWhoseSizeTheApiOmitsHasNoSize() {
        GcsResource resource = crawler.convertObjectToResource(
                blob("unknown.bin", null), bucket("demo-bucket"), PROJECT, OBJECT_TYPE);

        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
        assertThat(resource.getTechnicalMetadata()).doesNotContainKey(CrawlerAssetIds.SIZE_BYTES_KEY);
    }

    @Test
    void theSameBucketAndObjectNamesInTwoBucketsGetDistinctIds() {
        GcsResource a = crawler.convertObjectToResource(blob("same.csv", 1L), bucket("bucket-a"), PROJECT, OBJECT_TYPE);
        GcsResource b = crawler.convertObjectToResource(blob("same.csv", 1L), bucket("bucket-b"), PROJECT, OBJECT_TYPE);

        assertThat(List.of(a.getUri(), b.getUri())).containsExactly("gs://bucket-a/same.csv", "gs://bucket-b/same.csv");
    }

    @Test
    void theBridgeResolvesTheSameIdsTheCrawlerStored() {
        GcsResource bucket = crawler.convertBucketToResource(bucket("demo-bucket"), PROJECT, BUCKET_TYPE);
        GcsResource object = crawler.convertObjectToResource(
                blob("a/b.csv", 5L), bucket("demo-bucket"), PROJECT, OBJECT_TYPE);

        assertThat(CrawlerAssetIds.resolve(bucket)).hasValueSatisfying(id -> assertThat(id.uri()).isEqualTo(bucket.getUri()));
        assertThat(CrawlerAssetIds.resolve(object)).hasValueSatisfying(id -> assertThat(id.uri()).isEqualTo(object.getUri()));
    }
}
