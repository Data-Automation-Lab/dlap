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

package com.dlap.discovery.log.service.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.log.service.aws.adapter.AWSLogEntryAdapter;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Real-mapping honesty test for {@link AWSLogAnalyzer}.
 *
 * <p>Proves the AWS log path maps a faithful CloudTrail S3 {@code PutObject}
 * entry onto a {@link ResourceChange} correctly: every asserted field traces
 * to the synthetic fixture below (no invented data). The synthetic entry lives
 * only in this test - never on a production path.
 */
@ExtendWith(MockitoExtension.class)
class AWSLogAnalyzerTest {

    // The AWS S3 ResourceType id the analyzer maps "s3.amazonaws.com" to.
    private static final String AWS_S3_TYPE_ID = "aws_s3_bucket";

    @Mock
    private CloudHierarchyRegistry hierarchyRegistry;

    @Mock
    private KafkaTemplate<String, LogEvent> kafkaTemplate;

    /**
     * Faithful CloudTrail S3 PutObject event. Field shapes mirror a real
     * CloudTrail record: eventName/eventSource/eventTime/awsRegion/userIdentity
     * plus a resources map carrying resourceName + resourceId (ARN).
     */
    private AWSLogEntryAdapter syntheticS3PutObject() {
        return new AWSLogEntryAdapter.Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .eventName("PutObject")
                .eventSource("s3.amazonaws.com")
                .eventType("AwsApiCall")
                .eventTime(Instant.parse("2026-07-04T12:00:00Z"))
                .awsRegion("us-east-1")
                .sourceIpAddress("203.0.113.10")
                .userIdentity("arn:aws:iam::123456789012:user/analyst")
                .userAgent("aws-cli/2.15.0")
                .resources(Map.of(
                        "resourceName", "finance-reports",
                        "resourceId", "arn:aws:s3:::finance-reports"))
                .build();
    }

    @Test
    void mapsRealCloudTrailPutObjectToResourceChange() {
        // Real ResourceType resolved from the registry for the mapped id.
        CloudService s3Service = new CloudService("aws_s3", "S3", CloudProvider.AWS);
        ResourceType s3Type = new ResourceType(AWS_S3_TYPE_ID, "S3 Bucket", s3Service);
        when(hierarchyRegistry.getResourceType(AWS_S3_TYPE_ID)).thenReturn(s3Type);

        // authService/configService/loggingAdapter are not needed for the
        // single-entry mapping seam; pass null (constructor tolerates it).
        AWSLogAnalyzer analyzer = new AWSLogAnalyzer(
                null, null, hierarchyRegistry, null, kafkaTemplate);
        // @Value is not injected under a plain constructor; set the topic that
        // Spring would inject from its default so we assert against the real name.
        ReflectionTestUtils.setField(analyzer, "logEventsTopic", "dlap.log.event.normalized");

        // Drive the analyzer's real mapping via its package-visible single-entry seam.
        analyzer.processSingleLogEntry("123456789012", syntheticS3PutObject(), null);

        ArgumentCaptor<LogEvent> eventCaptor = ArgumentCaptor.forClass(LogEvent.class);
        // AWS publishes with event.getEventId() (random UUID) as the key.
        verify(kafkaTemplate).send(eq("dlap.log.event.normalized"), any(), eventCaptor.capture());

        LogEvent published = eventCaptor.getValue();
        assertEquals("AWS", published.getCloudProvider(), "provider must be AWS");

        ResourceChange change = published.getPayload();
        assertNotNull(change, "a ResourceChange must be published");
        // resourceId comes straight from the fixture's resources.resourceId (ARN).
        assertEquals("arn:aws:s3:::finance-reports", change.getResourceId());
        // PutObject contains "Put" -> UPDATE per EVENT_TO_CHANGE_TYPE.
        assertEquals(ResourceChange.ChangeType.UPDATE, change.getChangeType());
        // Provider on the change traces via the mapped ResourceType's service.
        assertEquals(CloudProvider.AWS, change.getCloudProvider());
        assertEquals(AWS_S3_TYPE_ID, change.getTypeId());
        // Timestamp is the fixture eventTime, not a fabricated now().
        assertEquals(Instant.parse("2026-07-04T12:00:00Z"), change.getTimestamp());
    }
}
