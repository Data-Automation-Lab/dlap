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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.aws.GlueResource;
import com.dlap.discovery.crawler.service.aws.GlueResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.Table;

/**
 * N5: unit test for {@link GlueResourceCrawler} - walks the Glue Data
 * Catalog (databases → tables) and publishes a {@link ResourceEvent}
 * per table. Drives a mocked {@link GlueClient} (LocalStack community
 * does not implement Glue (Pro feature), so the SDK-call seam is
 * mocked here; the identical, LocalStack-verified S3 path proves the
 * live AWS-SDK integration via {@link AwsClientFactory}).
 */
class GlueResourceCrawlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void mapsTablesAndPublishesResourceEvents() {
        GlueClient glue = mock(GlueClient.class);
        when(glue.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(Database.builder().name("analytics").build())
                        .build());
        when(glue.getTables(any(GetTablesRequest.class)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(
                                Table.builder()
                                        .name("events")
                                        .databaseName("analytics")
                                        .tableType("EXTERNAL_TABLE")
                                        .createTime(Instant.now())
                                        .storageDescriptor(StorageDescriptor.builder()
                                                .location("s3://dlap-demo/analytics/events/")
                                                .build())
                                        .parameters(Map.of("classification", "parquet"))
                                        .build(),
                                Table.builder()
                                        .name("users")
                                        .databaseName("analytics")
                                        .tableType("EXTERNAL_TABLE")
                                        .createTime(Instant.now())
                                        .storageDescriptor(StorageDescriptor.builder()
                                                .location("s3://dlap-demo/analytics/users/")
                                                .build())
                                        .parameters(Map.of("classification", "csv"))
                                        .build())
                        .build());

        AwsClientFactory clientFactory = mock(AwsClientFactory.class);
        when(clientFactory.glueClient(any(Region.class))).thenReturn(glue);

        AWSConfigService configService = mock(AWSConfigService.class);
        when(configService.getRegion()).thenReturn("us-east-1");
        when(configService.getAccountId()).thenReturn("123456789012");

        ResourceType glueType = new ResourceType(
                AWSConstants.RESOURCE_TYPE_GLUE_TABLE, "Glue Table",
                new CloudService("glue", "AWS Glue", CloudProvider.AWS));
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(AWSConstants.RESOURCE_TYPE_GLUE_TABLE)).thenReturn(glueType);

        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);

        GlueResourceCrawler crawler = new GlueResourceCrawler(registry, configService, kafka, clientFactory);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", "123456789012", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(2)).send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());

        Map<String, GlueResource> byId = new HashMap<>();
        events.getAllValues().forEach(e -> byId.put(e.getPayload().getResourceId(), (GlueResource) e.getPayload()));
        assertThat(byId.keySet()).containsExactlyInAnyOrder("analytics.events", "analytics.users");

        GlueResource events1 = byId.get("analytics.events");
        assertThat(events1.getCloudProviderEnum()).isEqualTo(CloudProvider.AWS);
        assertThat(events1.getDatabase()).isEqualTo("analytics");
        assertThat(events1.getTableType()).isEqualTo("EXTERNAL_TABLE");
        assertThat(events1.getLocation()).isEqualTo("s3://dlap-demo/analytics/events/");
        assertThat(events1.getClassification()).isEqualTo("parquet");
        assertThat(events1.getUri())
                .isEqualTo("arn:aws:glue:us-east-1:123456789012:table/analytics/events");
        assertThat(byId.get("analytics.users").getClassification()).isEqualTo("csv");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tableAndDatabaseNamesAreLowerCasedInTheCanonicalArnAsGlueStoresThem() {
        GlueClient glue = mock(GlueClient.class);
        when(glue.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(Database.builder().name("Analytics").build())
                        .build());
        when(glue.getTables(any(GetTablesRequest.class)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(Table.builder().name("Page_Views").databaseName("Analytics").build())
                        .build());
        AwsClientFactory clientFactory = mock(AwsClientFactory.class);
        when(clientFactory.glueClient(any(Region.class))).thenReturn(glue);
        AWSConfigService configService = mock(AWSConfigService.class);
        when(configService.getRegion()).thenReturn("eu-west-1");
        when(configService.getAccountId()).thenReturn("123456789012");
        ResourceType glueType = new ResourceType(
                AWSConstants.RESOURCE_TYPE_GLUE_TABLE, "Glue Table",
                new CloudService("glue", "AWS Glue", CloudProvider.AWS));
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(AWSConstants.RESOURCE_TYPE_GLUE_TABLE)).thenReturn(glueType);
        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);
        GlueResourceCrawler crawler = new GlueResourceCrawler(registry, configService, kafka, clientFactory);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", "123456789012", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(1)).send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());
        // The inventory identity keeps the spelling Glue returned; the canonical id folds the case.
        assertThat(events.getValue().getPayload().getResourceId()).isEqualTo("Analytics.Page_Views");
        assertThat(events.getValue().getPayload().getUri())
                .isEqualTo("arn:aws:glue:eu-west-1:123456789012:table/analytics/page_views");
    }
}
