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

package com.dlap.discovery.log.service.azure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.providers.azure.auth.AzureAuthenticationService;
import com.dlap.discovery.providers.azure.config.AzureConfigService;

/**
 * De-fabrication lock for {@link AzureLogAnalyzer}.
 *
 * <p>authService and configService are mocked non-null so the null-guard early
 * returns do NOT fire - this exercises the real de-fabricated analysis body and
 * proves it publishes NOTHING (the prior implementation fabricated synthetic
 * "azure-vm-&lt;uuid&gt;" CREATE events onto the catalog spine).
 */
@ExtendWith(MockitoExtension.class)
class AzureLogAnalyzerTest {

    @Mock
    private AzureAuthenticationService authService;

    @Mock
    private AzureConfigService configService;

    @Mock
    private CloudHierarchyRegistry hierarchyRegistry;

    @Mock
    private KafkaTemplate<String, LogEvent> kafkaTemplate;

    private AzureLogAnalyzer newAnalyzer() {
        return new AzureLogAnalyzer(authService, configService, hierarchyRegistry, kafkaTemplate);
    }

    @Test
    void triggerLogAnalysisPublishesNothing() {
        AzureLogAnalyzer analyzer = newAnalyzer();

        analyzer.triggerLogAnalysisAsync(
                "sub-123",
                ZonedDateTime.parse("2026-07-04T00:00:00Z"),
                ZonedDateTime.parse("2026-07-04T01:00:00Z"),
                null);

        // Never fabricates or publishes a ResourceChange to Kafka.
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void processLogEventPublishesNothing() {
        AzureLogAnalyzer analyzer = newAnalyzer();

        analyzer.processLogEventAsync("sub-123", new Object(), null);

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void hasNewLogsIsAlwaysFalse() {
        AzureLogAnalyzer analyzer = newAnalyzer();

        assertFalse(analyzer.hasNewLogs("sub-123", Instant.parse("2026-07-04T00:00:00Z")),
                "unimplemented Azure integration must report no new logs");
    }
}
