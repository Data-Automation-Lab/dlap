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

package com.dlap.discovery.crawler.service.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.azure.auth.AzureAuthenticationService;
import com.dlap.discovery.providers.azure.config.AzureConfigService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * De-fabrication lock for {@link AzureVirtualMachineCrawler} (ADR-0017).
 *
 * <p>The registry resolves the {@code azure_vm} ResourceType and the auth/config
 * services are mocked non-null, so no early guard fires - this exercises the real
 * discovery body and proves it publishes NOTHING. The prior implementation
 * fabricated three synthetic {@code sample-vm-*} CREATE events per run onto the
 * discovery spine, where the crawler→catalog bridge (ADR-0033) turned them into
 * catalog assets indistinguishable from real discoveries.
 */
class AzureVirtualMachineCrawlerTest {

    private static final ResourceType VM_TYPE = new ResourceType(
            "azure_vm", "Virtual Machine",
            new CloudService("azure_compute", "Azure Compute", CloudProvider.AZURE));

    private ListAppender<ILoggingEvent> logAppender;
    private Logger crawlerLogger;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate = mock(KafkaTemplate.class);

    @BeforeEach
    void attachAppender() {
        crawlerLogger = (Logger) LoggerFactory.getLogger(AzureVirtualMachineCrawler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        crawlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        crawlerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private AzureVirtualMachineCrawler newCrawler() {
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType("azure_vm")).thenReturn(VM_TYPE);
        AzureVirtualMachineCrawler crawler = new AzureVirtualMachineCrawler(
                registry,
                mock(AzureAuthenticationService.class),
                mock(AzureConfigService.class),
                kafkaTemplate);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");
        return crawler;
    }

    @Test
    void discoveryPublishesNoResources() {
        AzureVirtualMachineCrawler crawler = newCrawler();

        crawler.discoverResourcesAsync("sub-123", Map.of());

        // Never fabricates or publishes a ResourceEvent to Kafka.
        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(kafkaTemplate, never()).send(any(), any());
    }

    @Test
    void discoveryWarnsThatAzureVmDiscoveryIsNotImplemented() {
        AzureVirtualMachineCrawler crawler = newCrawler();

        crawler.discoverResourcesAsync("sub-123", Map.of());

        assertThat(logAppender.list)
                .as("an unimplemented provider must warn loudly instead of fabricating")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("Azure VM discovery is not implemented")
                            .contains("sub-123")
                            .contains("NO resources");
                });
    }

    @Test
    void prepareDiscoveryPublishesNothingEither() {
        AzureVirtualMachineCrawler crawler = newCrawler();

        crawler.prepareDiscovery(new DiscoveryJob());

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }
}
