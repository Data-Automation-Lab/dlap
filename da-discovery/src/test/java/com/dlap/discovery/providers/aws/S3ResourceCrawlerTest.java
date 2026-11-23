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

package com.dlap.discovery.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.aws.S3Resource;
import com.dlap.discovery.crawler.service.aws.S3ResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

/**
 * Unit test of {@link S3ResourceCrawler} with a mocked S3 client, so the canonical id and creation
 * time of a bucket are pinned without Docker (the LocalStack test covers the live SDK path).
 */
class S3ResourceCrawlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void bucketsGetCanonicalUrisAndTheirCreationTimes() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listBuckets()).thenReturn(ListBucketsResponse.builder().buckets(
                Bucket.builder().name("dlap-demo").creationDate(Instant.parse("2025-03-01T10:00:00Z")).build(),
                // Buckets created in us-east-1 before 2018-03-01 may contain upper-case letters.
                Bucket.builder().name("Legacy-Bucket").creationDate(Instant.parse("2017-06-01T00:00:00Z")).build())
                .build());

        AwsClientFactory clientFactory = mock(AwsClientFactory.class);
        when(clientFactory.s3Client(any(Region.class))).thenReturn(s3);

        AWSConfigService configService = mock(AWSConfigService.class);
        when(configService.getRegion()).thenReturn("us-east-1");
        when(configService.getAccountId()).thenReturn("123456789012");

        ResourceType s3Type = new ResourceType(AWSConstants.RESOURCE_TYPE_S3_BUCKET, "S3 Bucket",
                new CloudService("s3", "Amazon S3", CloudProvider.AWS));
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(AWSConstants.RESOURCE_TYPE_S3_BUCKET)).thenReturn(s3Type);

        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);
        S3ResourceCrawler crawler = new S3ResourceCrawler(registry, configService, kafka, clientFactory);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", "123456789012",
                new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(2)).send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());

        Set<String> uris = new HashSet<>();
        events.getAllValues().forEach(e -> {
            S3Resource resource = (S3Resource) e.getPayload();
            uris.add(resource.getUri());
            // Account and region are attributes, not identity.
            assertThat(resource.getAccountId()).isEqualTo("123456789012");
            assertThat(CrawlerAssetIds.resolve(resource))
                    .hasValueSatisfying(id -> assertThat(id.uri()).isEqualTo(resource.getUri()));
            // An S3 bucket's size is not in the listing: unknown, not zero.
            assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
            if ("dlap-demo".equals(resource.getResourceId())) {
                assertThat(resource.getCreatedAt()).isEqualTo(Instant.parse("2025-03-01T10:00:00Z"));
            }
        });
        assertThat(uris).containsExactlyInAnyOrder("s3://dlap-demo", "s3://Legacy-Bucket");
    }
}
