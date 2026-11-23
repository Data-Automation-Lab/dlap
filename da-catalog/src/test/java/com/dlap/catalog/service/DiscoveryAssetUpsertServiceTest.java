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

package com.dlap.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogEvent;
import com.dlap.catalog.kafka.CatalogEventDecoder;
import com.dlap.catalog.kafka.CatalogIngestMetrics;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.util.Timestamps;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;

/**
 * Row creation and refresh from discovery evidence: canonical identity with provider and type
 * from the ID, the raw form as the native id, operational scan times from the injected clock,
 * and change notifications only after the transaction commits.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryAssetUpsertServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private static final Instant EVENT_TIME = Instant.parse("2026-10-01T10:00:00Z");

    @Mock
    private AssetService assetService;

    @Mock
    private CanonicalUriIndex index;

    @Mock
    private EntityManager entityManager;

    private final CatalogEventDecoder decoder = new CatalogEventDecoder(new CatalogIngestProperties(),
            new CatalogIngestMetrics(new SimpleMeterRegistry()));

    private DiscoveryAssetUpsertService service;

    @BeforeEach
    void setUp() {
        service = new DiscoveryAssetUpsertService(assetService, index, Clock.fixed(NOW, ZoneOffset.UTC));
        service.setEntityManager(entityManager);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private CatalogEvent event(String id, boolean crawler, String name, String region, Map<String, String> tech) {
        TargetResource.Builder target = TargetResource.newBuilder().setId(id).putAllTechnicalMetadata(tech);
        if (name != null) {
            target.setName(name);
        }
        if (region != null) {
            target.setRegion(region);
        }
        DlapLogEvent e = DlapLogEvent.newBuilder()
                .setEventId("e-" + id.hashCode())
                .setEventTime(Timestamps.fromMillis(EVENT_TIME.toEpochMilli()))
                .setSourceProvider(SourceProvider.GCP)
                .setChangeType(ChangeType.CREATE)
                .setResultStatus(ResultStatus.SUCCESS)
                .setOrigin(crawler ? Origin.ORIGIN_CRAWLER : Origin.ORIGIN_CLOUD_LOG)
                .setTargetResource(target)
                .build();
        return decoder.decode(e, "test");
    }

    @Test
    void createsTheRowWithCanonicalIdentityAndTheRawFormAsNativeId() {
        CatalogEvent event = event("projects/proj-one/datasets/sales/tables/orders", false, null, "us", Map.of("rows", "7"));

        Asset created = service.create(event, event.canonicalTarget().orElseThrow(), EVENT_TIME);

        verify(entityManager).persist(created);
        verify(entityManager).flush();
        verify(index).writeThrough(created.getAssetId(), "bq://proj-one/sales/orders", "BIGQUERY_TABLE", "GCP", "us");
        assertThat(created.getAssetUri()).isEqualTo("bq://proj-one/sales/orders");
        assertThat(created.getCloudProvider()).isEqualTo("GCP");
        assertThat(created.getServiceType()).isEqualTo("BIGQUERY_TABLE");
        assertThat(created.getNativeAssetId()).isEqualTo("projects/proj-one/datasets/sales/tables/orders");
        assertThat(created.getAssetName()).as("falls back to the last identity part").isEqualTo("orders");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getLifecycleEventTime()).isEqualTo(EVENT_TIME);
        assertThat(created.getDiscoveredAt()).isEqualTo(EVENT_TIME);
        assertThat(created.getDiscoverySource()).isEqualTo("AUDIT_LOG");
        assertThat(created.getLastScannedAt()).as("operational time from the clock").isEqualTo(NOW);
        assertThat(created.getTechnicalMetadata()).containsEntry("rows", "7");
        assertThat(created.getCloudCreatedAt()).as("business times are the applier's job").isNull();
    }

    @Test
    void aRowFromACrawlerObservationTakesTheReportedCreationTimeNotTheCrawlTimeAsItsLifecycleTime() {
        Instant createdAt = Instant.parse("2026-03-01T00:00:00Z");
        CatalogEvent withCreation = crawl("gs://crawled-bucket", createdAt);
        CatalogEvent withoutCreation = crawl("gs://crawled-bucket-two", null);
        CatalogEvent impossibleCreation = crawl("gs://crawled-bucket-three", EVENT_TIME.plusSeconds(60));

        Asset a = service.create(withCreation, withCreation.canonicalTarget().orElseThrow(), EVENT_TIME);
        Asset b = service.create(withoutCreation, withoutCreation.canonicalTarget().orElseThrow(), EVENT_TIME);
        Asset c = service.create(impossibleCreation, impossibleCreation.canonicalTarget().orElseThrow(), EVENT_TIME);

        assertThat(a.getLifecycleEventTime()).as("the provider creation is the transition").isEqualTo(createdAt);
        assertThat(b.getLifecycleEventTime()).as("a crawl is no transition: unknown").isNull();
        assertThat(c.getLifecycleEventTime()).as("a creation after the crawl is not evidence").isNull();
        assertThat(a.getDiscoveredAt()).as("first seen is still the crawl").isEqualTo(EVENT_TIME);
        assertThat(a.getStatus()).isEqualTo("ACTIVE");
        assertThat(a.getDiscoverySource()).isEqualTo("CRAWLER");
    }

    private CatalogEvent crawl(String id, Instant createdTime) {
        TargetResource.Builder target = TargetResource.newBuilder().setId(id);
        if (createdTime != null) {
            target.setCreatedTime(Timestamps.fromMillis(createdTime.toEpochMilli()));
        }
        return decoder.decode(DlapLogEvent.newBuilder()
                .setEventId("crawl-" + id.hashCode())
                .setEventTime(Timestamps.fromMillis(EVENT_TIME.toEpochMilli()))
                .setSourceProvider(SourceProvider.GCP)
                .setChangeType(ChangeType.CREATE)
                .setOrigin(Origin.ORIGIN_CRAWLER)
                .setTargetResource(target)
                .build(), "test");
    }

    @Test
    void aCrawlerObservationRefreshesNameAndRegionOtherEventsOnlyFillBlanks() {
        Asset asset = new Asset("gs://refresh-me", "GCP", "GCS_BUCKET", "old-name");
        asset.setRegion("us-east1");
        asset.setTechnicalMetadata(new HashMap<>(Map.of("storageClass", "STANDARD")));

        service.refresh(asset, event("gs://refresh-me", false, "projects/_/buckets/refresh-me", "eu",
                Map.of("versioning", "Enabled")));
        assertThat(asset.getAssetName()).isEqualTo("old-name");
        assertThat(asset.getRegion()).isEqualTo("us-east1");
        assertThat(asset.getTechnicalMetadata()).containsEntry("storageClass", "STANDARD")
                .containsEntry("versioning", "Enabled");
        assertThat(asset.getNativeAssetId()).isEqualTo("gs://refresh-me");

        service.refresh(asset, event("projects/_/buckets/refresh-me", true, "refresh-me", "europe-west1", Map.of()));
        assertThat(asset.getAssetName()).isEqualTo("refresh-me");
        assertThat(asset.getRegion()).isEqualTo("europe-west1");
        assertThat(asset.getNativeAssetId()).as("the first native form seen is kept").isEqualTo("gs://refresh-me");
    }

    @Test
    void changeEventsArePublishedOnlyAfterCommit() {
        Asset asset = new Asset("gs://publish-me", "GCP", "GCS_BUCKET");
        TransactionSynchronizationManager.initSynchronization();

        service.publishAfterCommit(asset, "ASSET_UPDATED");
        verify(assetService, never()).publishAssetChangeEvent(any(), anyString(), any());

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        ArgumentCaptor<Asset> published = ArgumentCaptor.forClass(Asset.class);
        verify(assetService).publishAssetChangeEvent(published.capture(), eq("ASSET_UPDATED"), isNull());
        assertThat(published.getValue()).isSameAs(asset);
    }

    @Test
    void markScannedUsesTheClock() {
        Asset asset = new Asset("gs://scan-me", "GCP", "GCS_BUCKET");

        service.markScanned(asset);

        assertThat(asset.getLastScannedAt()).isEqualTo(NOW);
        assertThat(asset.getDlapLastScannedTs()).isEqualTo(NOW);
    }
}
