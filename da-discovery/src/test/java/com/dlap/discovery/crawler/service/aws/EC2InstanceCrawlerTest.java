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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.aws.EC2Resource;
import com.dlap.discovery.event.service.type.ResourceEvent;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests for EC2InstanceCrawler focusing on the event publishing aspect. Pure Mockito: no
 * Docker, Keycloak or Kafka is involved, so it carries no environment gate.
 */
class EC2InstanceCrawlerTest {

    // Mocks for dependencies NOT involved in the async method's core logic
    @Mock
    private AWSConfigService configService;

    @Mock
    private CloudHierarchyRegistry hierarchyRegistry;

    @Mock
    private KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @InjectMocks
    private EC2InstanceCrawler ec2Crawler; // Inject mocks into the class under test

    @Captor
    private ArgumentCaptor<String> topicCaptor;

    @Captor
    private ArgumentCaptor<String> keyCaptor;

    @Captor
    private ArgumentCaptor<ResourceEvent> eventCaptor;

    private ResourceType awsEc2ResourceType;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Initialize necessary mock behavior, e.g., config service returning region
        when(configService.getRegion()).thenReturn("us-east-1");

        // Create and register the AWS EC2 resource type
        CloudService ec2Service = new CloudService("aws_ec2", "EC2", CloudProvider.AWS);
        awsEc2ResourceType = new ResourceType("aws_ec2_instance", "EC2 Instance", ec2Service);
        when(hierarchyRegistry.getResourceType("aws_ec2_instance")).thenReturn(awsEc2ResourceType);

        // Mock KafkaTemplate send to succeed
        CompletableFuture<SendResult<String, ResourceEvent>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), any(ResourceEvent.class))).thenReturn(future);
    }

    @Test
    void testDiscoverResourcesAsync_CallsPerformActualDiscovery() {
        // Arrange
        String accountId = "123456789012";
        Map<String, Object> context = Map.of();

        // Use spy to verify the async method is called,
        // but prevent its actual execution in this *unit* test.
        EC2InstanceCrawler spyCrawler = spy(ec2Crawler);
        doNothing().when(spyCrawler).performActualDiscovery(anyString(), any());

        // Act
        spyCrawler.discoverResourcesAsync(accountId, context);

        // Assert
        // Verify that the async method was called with the correct parameters
        verify(spyCrawler).performActualDiscovery(eq(accountId), eq(context));
    }

    @Test
    void testPublishResourceEvent_SendsCorrectKafkaMessage() {
        // This test verifies that the publishResourceEvent helper method
        // correctly constructs and sends a ResourceEvent to Kafka.
        // It does NOT test the logic within performActualDiscovery that fetches
        // resources from AWS.

        // Arrange
        String accountId = "123456789012";
        EC2Resource mockEc2Resource = new EC2Resource(awsEc2ResourceType, "i-12345", "test-instance");

        // Call the helper method directly for this test
        ec2Crawler.publishResourceEvent(accountId, mockEc2Resource, ChangeType.CREATE);

        // Assert
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());

        // Verify key
        assertEquals("i-12345", keyCaptor.getValue());

        // Verify event content
        ResourceEvent publishedEvent = eventCaptor.getValue();
        assertNotNull(publishedEvent);
        assertEquals("AWS", publishedEvent.getCloudProvider());
        assertEquals(accountId, publishedEvent.getAccountId());
        assertEquals(ChangeType.CREATE, publishedEvent.getChangeType());
        assertNotNull(publishedEvent.getEventId()); // Check if event ID is generated
        assertNotNull(publishedEvent.getEventTimestamp()); // Check if timestamp is set
        assertEquals("Crawler", publishedEvent.getSource()); // Check source
        assertSame(mockEc2Resource, publishedEvent.getPayload()); // Use assertSame for mock comparison
    }
    // Add more tests for different scenarios: errors, filters, no resources found
    // etc.
}
