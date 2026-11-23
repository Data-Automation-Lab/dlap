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

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.service.aws.S3ResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * N5 smoke test: the {@link S3ResourceCrawler} discovers real S3 buckets
 * against a LocalStack S3 endpoint and publishes a {@link ResourceEvent}
 * per bucket. Proves the AWS S3 vertical end-to-end (SDK call through
 * {@link AwsClientFactory}'s endpoint override) without touching real AWS.
 */
@Testcontainers
class S3ResourceCrawlerLocalStackTest {

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.4"))
            .withServices(Service.S3);

    @BeforeAll
    static void seedBuckets() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket("dlap-demo").build());
            s3.createBucket(CreateBucketRequest.builder().bucket("dlap-audit-logs").build());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoversBucketsAndPublishesResourceEvents() {
        // AwsClientFactory pointed at LocalStack with its static creds.
        AwsClientFactory clientFactory = new AwsClientFactory();
        ReflectionTestUtils.setField(clientFactory, "endpointOverride", LOCALSTACK.getEndpoint().toString());
        ReflectionTestUtils.setField(clientFactory, "accessKey", LOCALSTACK.getAccessKey());
        ReflectionTestUtils.setField(clientFactory, "secretKey", LOCALSTACK.getSecretKey());

        AWSConfigService configService = mock(AWSConfigService.class);
        when(configService.getRegion()).thenReturn(LOCALSTACK.getRegion());
        when(configService.getAccountId()).thenReturn("000000000000");

        ResourceType s3Type = new ResourceType(
                AWSConstants.RESOURCE_TYPE_S3_BUCKET, "S3 Bucket",
                new CloudService("s3", "Amazon S3", CloudProvider.AWS));
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(AWSConstants.RESOURCE_TYPE_S3_BUCKET)).thenReturn(s3Type);

        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);

        S3ResourceCrawler crawler = new S3ResourceCrawler(registry, configService, kafka, clientFactory);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", "000000000000", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(2)).send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());

        Map<String, ResourceEvent> byBucket = new HashMap<>();
        events.getAllValues().forEach(e -> byBucket.put(e.getPayload().getResourceId(), e));
        assertThat(byBucket.keySet()).containsExactlyInAnyOrder("dlap-demo", "dlap-audit-logs");
        assertThat(byBucket.get("dlap-demo").getPayload().getCloudProviderEnum()).isEqualTo(CloudProvider.AWS);
        assertThat(byBucket.get("dlap-demo").getPayload().getUri()).isEqualTo("s3://dlap-demo");
    }
}
