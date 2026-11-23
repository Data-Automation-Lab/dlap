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

package com.dlap.discovery.crawler.service.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.aws.EC2Resource;
import com.dlap.discovery.providers.aws.config.AWSConfigService;

import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Reservation;

/**
 * The EC2 crawler finds its resource type whichever id the cloud-hierarchy registry declares.
 *
 * <p>The shipped hierarchy ({@code application.yml}, {@code cloud-hierarchy}) names the type
 * {@code EC2_INSTANCE}, while the crawler asked for {@code aws_ec2_instance}. The lookup found
 * nothing, the crawler registered no type, and every instance was dropped with a "type not found"
 * log line: an EC2 crawl published nothing. These tests pin that either id works and that an
 * instance is never mapped without a type. They need no Docker.
 */
class EC2InstanceCrawlerTypeTest {

    private static final CloudService EC2_SERVICE = new CloudService("ec2", "EC2", CloudProvider.AWS);

    private static final Instance INSTANCE = Instance.builder()
            .instanceId("i-0abc123def456")
            .instanceType(InstanceType.T3_MICRO)
            .launchTime(Instant.parse("2026-03-04T05:06:07Z"))
            .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
            .build();

    private static final Reservation RESERVATION = Reservation.builder().ownerId("123456789012").build();

    @SuppressWarnings("unchecked")
    private static EC2InstanceCrawler crawlerWith(CloudHierarchyRegistry registry) {
        return new EC2InstanceCrawler(registry, mock(AWSConfigService.class), mock(KafkaTemplate.class));
    }

    @Test
    void registersTheTypeTheShippedHierarchyDeclares() {
        ResourceType shipped = new ResourceType(EC2Resource.HIERARCHY_TYPE_ID, "EC2 Instance", EC2_SERVICE);
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(EC2Resource.HIERARCHY_TYPE_ID)).thenReturn(shipped);

        EC2InstanceCrawler crawler = crawlerWith(registry);

        assertThat(crawler.getSupportedResourceTypes()).containsExactly(shipped);
        assertThat(crawler.findSupportedType()).isSameAs(shipped);
    }

    @Test
    void prefersTheCrawlersOwnConstantWhenTheHierarchyDeclaresIt() {
        ResourceType byConstant = new ResourceType(EC2Resource.TYPE_ID, "EC2 Instance", EC2_SERVICE);
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(EC2Resource.TYPE_ID)).thenReturn(byConstant);
        when(registry.getResourceType(EC2Resource.HIERARCHY_TYPE_ID))
                .thenReturn(new ResourceType(EC2Resource.HIERARCHY_TYPE_ID, "EC2 Instance", EC2_SERVICE));

        EC2InstanceCrawler crawler = crawlerWith(registry);

        assertThat(crawler.getSupportedResourceTypes()).containsExactly(byConstant);
    }

    @Test
    void anInstanceIsMappedUnderTheHierarchyType() {
        ResourceType shipped = new ResourceType(EC2Resource.HIERARCHY_TYPE_ID, "EC2 Instance", EC2_SERVICE);
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(EC2Resource.HIERARCHY_TYPE_ID)).thenReturn(shipped);
        EC2InstanceCrawler crawler = crawlerWith(registry);

        EC2Resource resource = crawler.mapInstanceToEC2Resource(INSTANCE, RESERVATION, "us-east-1",
                crawler.findSupportedType());

        assertThat(resource).isNotNull();
        assertThat(resource.getResourceId()).isEqualTo("i-0abc123def456");
        assertThat(resource.getTypeId()).isEqualTo(EC2Resource.HIERARCHY_TYPE_ID);
        assertThat(resource.getAccountId()).isEqualTo("123456789012");
        assertThat(resource.getCreatedAt()).isEqualTo(Instant.parse("2026-03-04T05:06:07Z"));
        // Either id names an EC2 instance wherever the type is matched.
        assertThat(resource.getProviderSpecificType()).isEqualTo("AWS::EC2::Instance");
    }

    @Test
    void aVirtualMachineIsNotAnAssetKindSoItHasNoCanonicalId() {
        ResourceType shipped = new ResourceType(EC2Resource.HIERARCHY_TYPE_ID, "EC2 Instance", EC2_SERVICE);
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(EC2Resource.HIERARCHY_TYPE_ID)).thenReturn(shipped);
        EC2InstanceCrawler crawler = crawlerWith(registry);

        EC2Resource resource = crawler.mapInstanceToEC2Resource(INSTANCE, RESERVATION, "us-east-1", shipped);

        // ADR-0047 defines no kind for a virtual machine: the crawler writes no uri and the bridge
        // counts the event as unresolved instead of inventing an identity.
        assertThat(resource.getUri()).isNull();
        assertThat(CrawlerAssetIds.resolve(resource)).isEmpty();
    }

    @Test
    void withNoTypeInTheHierarchyNothingIsMappedAndTheCrawlerSaysSo() {
        EC2InstanceCrawler crawler = crawlerWith(mock(CloudHierarchyRegistry.class));

        assertThat(crawler.getSupportedResourceTypes()).isEmpty();
        assertThat(crawler.findSupportedType()).isNull();
        assertThat(crawler.mapInstanceToEC2Resource(INSTANCE, RESERVATION, "us-east-1", null)).isNull();
    }

    @Test
    void theEc2TypeIdsAreRecognisedByBothSpellings() {
        assertThat(EC2Resource.isEc2TypeId("aws_ec2_instance")).isTrue();
        assertThat(EC2Resource.isEc2TypeId("EC2_INSTANCE")).isTrue();
        assertThat(EC2Resource.isEc2TypeId("aws_s3_bucket")).isFalse();
        assertThat(EC2Resource.isEc2TypeId(null)).isFalse();
    }
}
