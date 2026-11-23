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

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DecodedLogEvent;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.aws.EC2Resource;
import com.dlap.discovery.crawler.model.gcp.BigQueryResource;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Producer contract of the crawler-to-spine bridge (ADR-0045, ADR-0046, ADR-0047): what a crawl
 * puts on {@code dlap.log.event.normalized} is read back by {@link DlapLogEventCodec} with the
 * canonical URI, the creation time and the observed size in their typed fields, and with no field
 * the contract does not know. The decode is the check da-catalog and the router perform.
 */
class CrawlerAssetSpineBridgeContractTest {

    private static final String PROJECT = "dlap-demo";
    private static final Instant CRAWLED_AT = Instant.parse("2026-10-19T09:00:00Z");
    private static final CloudService GCS = new CloudService("gcs", "Google Cloud Storage", CloudProvider.GCP);
    private static final ResourceType OBJECT_TYPE = new ResourceType("gcp_gcs_object", "GCS Object", GCS);
    private static final ResourceType BUCKET_TYPE = new ResourceType("gcp_gcs_bucket", "GCS Bucket", GCS);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private KafkaTemplate<String, String> kafka;
    private CrawlerAssetSpineBridge bridge;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(), any(), any());
        bridge = new CrawlerAssetSpineBridge(kafka, objectMapper, meters);
    }

    private static ResourceEvent crawl(com.dlap.discovery.common.model.CloudResource resource, String eventId) {
        ResourceEvent event = new ResourceEvent(CloudProvider.GCP.toString(), PROJECT, ChangeType.CREATE, resource);
        event.setEventId(eventId);
        event.setEventTimestamp(CRAWLED_AT);
        return event;
    }

    private static GcsResource object(String bucket, String key, Long size) {
        GcsResource resource = new GcsResource(OBJECT_TYPE, key, "gs://" + bucket + "/" + key);
        resource.setProjectId(PROJECT);
        resource.setAccountId(PROJECT);
        CrawlerAssetIds.assignBuilt(resource, com.dlap.common.assetid.AssetKind.GCS_OBJECT, bucket, key);
        CrawlerAssetIds.recordSize(resource, size);
        resource.setCreatedAt(Instant.parse("2026-01-15T12:00:00Z"));
        return resource;
    }

    private String publish(ResourceEvent event) {
        bridge.process(event);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), any(), value.capture());
        return value.getValue();
    }

    @Test
    void theEventCarriesTheCanonicalUriCreationTimeAndSizeInTheirTypedFields() {
        String json = publish(crawl(object("demo-bucket", "reports/q3.csv", 2048L), "crawl-1"));

        DecodedLogEvent decoded = codec.decodeDetailed(json);
        TargetResource target = decoded.event().getTargetResource();

        assertThat(target.getCanonicalUri()).isEqualTo("gs://demo-bucket/reports/q3.csv");
        assertThat(target.getKind()).isEqualTo("GCS_OBJECT");
        assertThat(target.hasSizeBytes()).isTrue();
        assertThat(target.getSizeBytes()).isEqualTo(2048L);
        assertThat(target.hasCreatedTime()).isTrue();
        assertThat(Instant.ofEpochSecond(target.getCreatedTime().getSeconds(), target.getCreatedTime().getNanos()))
                .isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(decoded.event().getOrigin()).isEqualTo(Origin.ORIGIN_CRAWLER);
        assertThat(decoded.event().getSourceFormat()).isEqualTo("DLAP_CRAWLER/resource-event");
        assertThat(decoded.event().getChangeType()).isEqualTo(com.dlap.proto.log.v1.ChangeType.CREATE);
        assertThat(decoded.event().hasEventTime()).isTrue();
    }

    @Test
    void theEventUsesOnlyFieldsTheContractDefinesPlusTheTwoLegacyMarkers() {
        String json = publish(crawl(object("demo-bucket", "reports/q3.csv", 2048L), "crawl-1"));

        DecodedLogEvent decoded = codec.decodeDetailed(json);

        // The reader reports what it dropped or rewrote. The only legacy item is the "source" marker
        // (rewritten to origin and source_format); nothing of the new fields is unknown to it.
        assertThat(decoded.warnings()).noneMatch(w -> w.contains("canonicalUri") || w.contains("createdTime")
                || w.contains("sizeBytes") || w.contains("nativeEventId") || w.contains("eventId"));
        assertThat(decoded.event().getNativeEventId()).isEqualTo("DLAP_CRAWLER|crawl-1");
    }

    @Test
    void theEventIdIsTheSha256OfTheProviderAndTheNativeKey() {
        String json = publish(crawl(object("demo-bucket", "reports/q3.csv", 1L), "crawl-1"));

        String expected = LogEventIds.eventId("GCP", "DLAP_CRAWLER|crawl-1");
        assertThat(codec.decode(json).getEventId()).isEqualTo(expected).hasSize(64);
    }

    @Test
    void theSameCrawlerEventAlwaysGetsTheSameEventId() {
        ResourceEvent event = crawl(object("demo-bucket", "a.csv", 1L), "crawl-9");

        bridge.process(event);
        bridge.process(event);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka, org.mockito.Mockito.times(2)).send(any(), any(), value.capture());
        assertThat(codec.decode(value.getAllValues().get(0)).getEventId())
                .isEqualTo(codec.decode(value.getAllValues().get(1)).getEventId());
    }

    @Test
    void whenTheCrawlerEventHasNoIdTheKeyIsBuiltFromTheAssetTheChangeAndTheCrawlTime() {
        ResourceEvent event = crawl(object("demo-bucket", "a.csv", 1L), null);

        String json = publish(event);

        assertThat(codec.decode(json).getNativeEventId())
                .isEqualTo("DLAP_CRAWLER|gs://demo-bucket/a.csv|CREATE|2026-10-19T09:00:00Z");
    }

    @Test
    void anUnknownProviderKeepsTheIdTheCrawlerSent() throws Exception {
        ResourceEvent event = crawl(object("demo-bucket", "a.csv", 1L), "crawl-1");
        event.setCloudProvider("MYSTERY");

        bridge.process(event);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(any(), any(), value.capture());
        JsonNode node = objectMapper.readTree(value.getValue());
        assertThat(node.path("eventId").asText()).isEqualTo("crawl-1");
    }

    @Test
    void anUnobservedSizeIsAbsentAndAZeroSizeIsReal() {
        TargetResource unknown = codec.decode(publish(crawl(object("demo-bucket", "u.bin", null), "c1")))
                .getTargetResource();
        assertThat(unknown.hasSizeBytes()).isFalse();
    }

    @Test
    void anEmptyObjectIsForwardedWithSizeZero() {
        TargetResource empty = codec.decode(publish(crawl(object("demo-bucket", "_SUCCESS", 0L), "c2")))
                .getTargetResource();
        assertThat(empty.hasSizeBytes()).isTrue();
        assertThat(empty.getSizeBytes()).isZero();
    }

    @Test
    void aResourceWithNoCreationTimeForwardsNone() {
        GcsResource resource = object("demo-bucket", "a.csv", 1L);
        resource.setCreatedAt(null);

        TargetResource target = codec.decode(publish(crawl(resource, "c3"))).getTargetResource();

        assertThat(target.hasCreatedTime()).isFalse();
    }

    @Test
    void theIdStaysTheCrawlersIdentifierAndTheKeyIsUnchanged() {
        ResourceEvent event = crawl(object("demo-bucket", "reports/q3.csv", 2048L), "crawl-1");

        bridge.process(event);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED),
                eq("gs://demo-bucket/reports/q3.csv"), value.capture());
        TargetResource target = codec.decode(value.getValue()).getTargetResource();
        // For a crawler that stores its canonical URI the id and the canonical URI are the same string.
        assertThat(target.getId()).isEqualTo(target.getCanonicalUri());
    }

    @Test
    void aLegacyResourceWithOnlyANativeIdIsResolvedByTheBridge() {
        GcsResource legacy = new GcsResource(BUCKET_TYPE, "demo-bucket", "demo-bucket");
        legacy.setProjectId(PROJECT);

        bridge.process(crawl(legacy, "crawl-2"));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        // The key is the crawler's identifier, as before ADR-0047 item 8's re-key.
        verify(kafka).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), eq("demo-bucket"), value.capture());
        TargetResource target = codec.decode(value.getValue()).getTargetResource();
        assertThat(target.getId()).isEqualTo("demo-bucket");
        assertThat(target.getCanonicalUri()).isEqualTo("gs://demo-bucket");
        assertThat(target.getKind()).isEqualTo("GCS_BUCKET");
    }

    @Test
    void anObjectKnownOnlyByItsBareKeyIsNotGuessedIntoABucketAndIsCounted() {
        // An event from an older crawler build: no uri, and the resource id is the bare object key.
        GcsResource legacy = new GcsResource(OBJECT_TYPE, "reports/2026/q3.csv", "reports/2026/q3.csv");
        legacy.setProjectId(PROJECT);

        TargetResource target = codec.decode(publish(crawl(legacy, "crawl-obj"))).getTargetResource();

        assertThat(target.getId()).isEqualTo("reports/2026/q3.csv");
        assertThat(target.getCanonicalUri()).isEmpty();
        assertThat(meters.get(CrawlerAssetSpineBridge.DISCOVERY_UNRESOLVED_COUNTER)
                .tag("serviceType", "gcp_gcs_object").counter().count()).isEqualTo(1.0);
    }

    @Test
    void anAssetThatDoesNotResolveIsPublishedWithoutACanonicalUriAndCounted() {
        EC2Resource vm = new EC2Resource(new ResourceType("aws_ec2_instance", "EC2",
                new CloudService("ec2", "EC2", CloudProvider.AWS)), "i-0abc123", "web");
        vm.setAccountId("123456789012");
        ResourceEvent event = new ResourceEvent(CloudProvider.AWS.toString(), "123456789012", ChangeType.CREATE, vm);
        event.setEventTimestamp(CRAWLED_AT);

        TargetResource target = codec.decode(publish(event)).getTargetResource();

        assertThat(target.getCanonicalUri()).isEmpty();
        assertThat(target.getId()).isEqualTo("i-0abc123");
        assertThat(target.getKind()).isEqualTo("aws_ec2_instance");
        assertThat(meters.get(CrawlerAssetSpineBridge.DISCOVERY_UNRESOLVED_COUNTER)
                .tag("provider", "AWS").tag("serviceType", "aws_ec2_instance").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("dlap.discovery.assets.total").counter().count()).isEqualTo(1.0);
    }

    /**
     * What the bridge sees at runtime is a ResourceEvent that crossed Kafka as JSON (the producer's
     * JsonSerializer, the consumer's JsonDeserializer with the "@class" typed payload). The id,
     * the hints and the size must survive that trip, or every asset would arrive unresolved.
     */
    @Test
    void aCrawledEventThatCrossedKafkaAsJsonStillResolvesAndKeepsItsSize() {
        ResourceEvent sent = crawl(object("demo-bucket", "reports/q3.csv", 2048L), "crawl-json");
        JsonSerializer<ResourceEvent> serializer = new JsonSerializer<>();
        JsonDeserializer<ResourceEvent> deserializer = new JsonDeserializer<>(ResourceEvent.class);
        deserializer.addTrustedPackages("*");
        deserializer.setUseTypeHeaders(false);

        ResourceEvent received = deserializer.deserialize("t", serializer.serialize("t", sent));

        assertThat(received.getPayload()).isInstanceOf(GcsResource.class);
        TargetResource target = codec.decode(publish(received)).getTargetResource();
        assertThat(target.getCanonicalUri()).isEqualTo("gs://demo-bucket/reports/q3.csv");
        assertThat(target.getKind()).isEqualTo("GCS_OBJECT");
        assertThat(target.getSizeBytes()).isEqualTo(2048L);
        assertThat(target.hasCreatedTime()).isTrue();
    }

    @Test
    void aLegacyEventThatCrossedKafkaAsJsonIsResolvedFromItsNativeIdAndHints() {
        GcsResource legacy = new GcsResource(BUCKET_TYPE, "demo-bucket", "demo-bucket");
        legacy.setProjectId(PROJECT);
        JsonSerializer<ResourceEvent> serializer = new JsonSerializer<>();
        JsonDeserializer<ResourceEvent> deserializer = new JsonDeserializer<>(ResourceEvent.class);
        deserializer.addTrustedPackages("*");
        deserializer.setUseTypeHeaders(false);

        ResourceEvent received = deserializer.deserialize("t", serializer.serialize("t", crawl(legacy, "crawl-legacy")));

        assertThat(codec.decode(publish(received)).getTargetResource().getCanonicalUri()).isEqualTo("gs://demo-bucket");
    }

    @Test
    void aResolvedAssetIsNotCountedAsUnresolved() {
        publish(crawl(object("demo-bucket", "a.csv", 1L), "c4"));

        assertThat(meters.find(CrawlerAssetSpineBridge.DISCOVERY_UNRESOLVED_COUNTER).counter()).isNull();
    }

    @Test
    void aBigQueryTableLegacyIdIsResolvedAndKeepsItsKind() {
        BigQueryResource table = new BigQueryResource(new ResourceType("gcp_bigquery_table", "Table",
                new CloudService("bigquery", "BigQuery", CloudProvider.GCP)), "dlap-demo:sales.orders", "sales.orders");
        table.setProjectId(PROJECT);

        TargetResource target = codec.decode(publish(crawl(table, "c5"))).getTargetResource();

        assertThat(target.getCanonicalUri()).isEqualTo("bq://dlap-demo/sales/orders");
        assertThat(target.getKind()).isEqualTo("BIGQUERY_TABLE");
    }
}
