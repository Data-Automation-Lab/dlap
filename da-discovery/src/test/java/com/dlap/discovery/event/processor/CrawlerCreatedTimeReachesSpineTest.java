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

package com.dlap.discovery.event.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.common.assetid.AssetKind;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.discovery.catalog.service.impl.CatalogServiceImpl;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudResourceFactory;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.CloudResourceRepository;
import com.dlap.discovery.common.model.repository.CloudResourceUsageStatsRepository;
import com.dlap.discovery.common.model.repository.ResourceChangeRepository;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.dlap.discovery.event.service.EventProcessorRegistry;
import com.dlap.discovery.event.service.ResourceEventListener;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Q-412 (FU-3C): the provider creation time of a crawled resource reaches the log spine as
 * {@code target_resource.created_time}, and a resource whose creation time the crawler did not read
 * carries none (ADR-0046, R6). The chain is the production one: the listener hands the same event
 * object to the catalog processor (priority 10), which persists the inventory row through the real
 * {@link CatalogServiceImpl}, and then to the spine bridge (priority 20), which prints what it finds.
 * Only the repositories and the Kafka template are mocks, so the old overwrite of {@code createdAt}
 * with the processing time in the inventory step shows up in the published event.
 */
class CrawlerCreatedTimeReachesSpineTest {

    private static final String PROJECT = "dlap-demo";
    private static final Instant CRAWLED_AT = Instant.parse("2026-10-19T09:00:00Z");
    private static final Instant PROVIDER_CREATED = Instant.parse("2026-01-15T00:00:00Z");
    private static final CloudService GCS = new CloudService("gcs", "Google Cloud Storage", CloudProvider.GCP);
    private static final ResourceType BUCKET_TYPE = new ResourceType("gcp_gcs_bucket", "GCS Bucket", GCS);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private KafkaTemplate<String, String> kafka;
    private CloudResourceRepository resources;
    private ResourceEventListener listener;
    private GenericApplicationContext context;
    private Acknowledgment ack;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(), any(), any());
        resources = mock(CloudResourceRepository.class);
        when(resources.findByResourceIdAndCloudProviderAndTypeId(any(), any(), any())).thenReturn(Optional.empty());
        when(resources.save(any(CloudResource.class))).thenAnswer(call -> call.getArgument(0));

        CatalogServiceImpl catalog = new CatalogServiceImpl(resources, mock(ResourceChangeRepository.class),
                mock(CloudResourceUsageStatsRepository.class), mock(CloudResourceFactory.class));
        CatalogResourceEventProcessor inventory = new CatalogResourceEventProcessor(catalog);
        CrawlerAssetSpineBridge bridge = new CrawlerAssetSpineBridge(kafka, objectMapper, new SimpleMeterRegistry());

        // The registry orders processors by priority, exactly as in the running service. The bridge is
        // registered first on purpose: the order must come from the priorities, not from registration.
        context = new GenericApplicationContext();
        context.registerBean("bridge", CrawlerAssetSpineBridge.class, () -> bridge);
        context.registerBean("inventory", CatalogResourceEventProcessor.class, () -> inventory);
        context.refresh();
        EventProcessorRegistry registry = new EventProcessorRegistry(context);
        registry.init();
        listener = new ResourceEventListener(registry);
        ack = mock(Acknowledgment.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static ResourceEvent crawl(Instant providerCreatedAt) {
        GcsResource bucket = new GcsResource(BUCKET_TYPE, "demo-bucket", "gs://demo-bucket");
        bucket.setProjectId(PROJECT);
        bucket.setAccountId(PROJECT);
        CrawlerAssetIds.assignBuilt(bucket, AssetKind.GCS_BUCKET, "demo-bucket");
        bucket.setCreatedAt(providerCreatedAt);
        ResourceEvent event = new ResourceEvent(CloudProvider.GCP.toString(), PROJECT, ChangeType.CREATE, bucket);
        event.setEventId("crawl-q412");
        event.setEventTimestamp(CRAWLED_AT);
        return event;
    }

    private TargetResource published() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), any(), value.capture());
        return codec.decodeDetailed(value.getValue()).event().getTargetResource();
    }

    @Test
    void aCrawlerResourceEventWithCreatedAtJan15ReachesTheSpineWithThatCreatedTime() {
        listener.handleResourceEvent(crawl(PROVIDER_CREATED), ack);

        TargetResource target = published();
        assertThat(target.hasCreatedTime()).isTrue();
        assertThat(Instant.ofEpochSecond(target.getCreatedTime().getSeconds(), target.getCreatedTime().getNanos()))
                .as("created_time on the spine").isEqualTo(PROVIDER_CREATED);
        verify(ack).acknowledge();
    }

    @Test
    void theInventoryRowKeepsTheProviderCreationTimeToo() {
        listener.handleResourceEvent(crawl(PROVIDER_CREATED), ack);

        ArgumentCaptor<CloudResource> saved = ArgumentCaptor.forClass(CloudResource.class);
        verify(resources).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(PROVIDER_CREATED);
    }

    @Test
    void anEventWithoutACreationTimeCarriesNoCreatedTimeOnTheSpine() {
        listener.handleResourceEvent(crawl(null), ack);

        assertThat(published().hasCreatedTime()).as("a guessed created_time").isFalse();
        verify(ack).acknowledge();
    }

    @Test
    void anEventWithoutACreationTimeLeavesTheInventoryCreatedAtEmpty() {
        listener.handleResourceEvent(crawl(null), ack);

        ArgumentCaptor<CloudResource> saved = ArgumentCaptor.forClass(CloudResource.class);
        verify(resources).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isNull();
        // The module's own stamps are still set: they are observations, not the provider's time.
        assertThat(saved.getValue().getLastDiscoveredAt()).isNotNull();
    }
}
