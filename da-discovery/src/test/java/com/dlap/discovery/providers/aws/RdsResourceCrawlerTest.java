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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.aws.RdsResource;
import com.dlap.discovery.crawler.service.aws.RdsResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.Endpoint;

/**
 * N5: unit test for {@link RdsResourceCrawler} - maps RDS
 * {@code DBInstance}s to {@link RdsResource} and publishes a
 * {@link ResourceEvent} per instance. Drives a mocked {@link RdsClient}
 * (LocalStack community does not implement RDS (Pro feature), so the
 * SDK-call seam is mocked here; the identical, LocalStack-verified S3
 * path proves the live AWS-SDK integration via {@link AwsClientFactory}).
 */
class RdsResourceCrawlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void mapsInstancesAndPublishesResourceEvents() {
        RdsClient rdsClient = mock(RdsClient.class);
        when(rdsClient.describeDBInstances(any(DescribeDbInstancesRequest.class)))
                .thenReturn(DescribeDbInstancesResponse.builder()
                        .dbInstances(DBInstance.builder()
                                .dbInstanceIdentifier("dlap-demo-db")
                                .engine("postgres")
                                .engineVersion("15.4")
                                .dbInstanceClass("db.t3.micro")
                                .dbInstanceStatus("available")
                                .storageEncrypted(true)
                                .instanceCreateTime(Instant.now())
                                .endpoint(Endpoint.builder().address("dlap-demo-db.abc.us-east-1.rds.amazonaws.com").build())
                                .build())
                        .build());

        AwsClientFactory clientFactory = mock(AwsClientFactory.class);
        when(clientFactory.rdsClient(any(Region.class))).thenReturn(rdsClient);

        AWSConfigService configService = mock(AWSConfigService.class);
        when(configService.getRegion()).thenReturn("us-east-1");
        when(configService.getAccountId()).thenReturn("123456789012");

        ResourceType rdsType = new ResourceType(
                AWSConstants.RESOURCE_TYPE_RDS_INSTANCE, "RDS Instance",
                new CloudService("rds", "Amazon RDS", CloudProvider.AWS));
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(AWSConstants.RESOURCE_TYPE_RDS_INSTANCE)).thenReturn(rdsType);

        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);

        RdsResourceCrawler crawler = new RdsResourceCrawler(registry, configService, kafka, clientFactory);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", "123456789012", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(1)).send(eq("dlap.discovery.asset.discovered"), eq("dlap-demo-db"), events.capture());

        RdsResource resource = (RdsResource) events.getValue().getPayload();
        assertThat(resource.getResourceId()).isEqualTo("dlap-demo-db");
        assertThat(resource.getCloudProviderEnum()).isEqualTo(CloudProvider.AWS);
        assertThat(resource.getEngine()).isEqualTo("postgres");
        assertThat(resource.getStatus()).isEqualTo("available");
        assertThat(resource.getStorageEncrypted()).isTrue();
        assertThat(resource.getEndpoint()).isEqualTo("dlap-demo-db.abc.us-east-1.rds.amazonaws.com");
        // Canonical id (ADR-0047): the ARN, from the connection's account and region.
        assertThat(resource.getUri()).isEqualTo("arn:aws:rds:us-east-1:123456789012:db:dlap-demo-db");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAnAccountNoArnIsWrittenInsteadOfOneThatSaysNull() {
        RdsClient rdsClient = mock(RdsClient.class);
        when(rdsClient.describeDBInstances(any(DescribeDbInstancesRequest.class)))
                .thenReturn(DescribeDbInstancesResponse.builder()
                        .dbInstances(DBInstance.builder().dbInstanceIdentifier("orphan-db").build())
                        .build());
        AwsClientFactory clientFactory = mock(AwsClientFactory.class);
        when(clientFactory.rdsClient(any(Region.class))).thenReturn(rdsClient);
        AWSConfigService configService = mock(AWSConfigService.class);
        when(configService.getRegion()).thenReturn("us-east-1");

        ResourceType rdsType = new ResourceType(
                AWSConstants.RESOURCE_TYPE_RDS_INSTANCE, "RDS Instance",
                new CloudService("rds", "Amazon RDS", CloudProvider.AWS));
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(AWSConstants.RESOURCE_TYPE_RDS_INSTANCE)).thenReturn(rdsType);
        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);
        RdsResourceCrawler crawler = new RdsResourceCrawler(registry, configService, kafka, clientFactory);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", null, new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(1)).send(eq("dlap.discovery.asset.discovered"), eq("orphan-db"), events.capture());
        assertThat(events.getValue().getPayload().getUri()).isNull();
    }
}
