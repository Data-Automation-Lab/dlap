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

package com.dlap.discovery.logspine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.aws.EC2Resource;
import com.dlap.discovery.crawler.model.gcp.BigQueryResource;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.dlap.discovery.event.processor.CrawlerAssetSpineBridge;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.log.service.LogAnalyzerSpinePublisher;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The wire contract of every da-discovery producer of {@code dlap.log.event.normalized}
 * (SPINE-01, log-event.md section 8): the crawler bridge and the log-analyzer publisher send, as
 * the broker receives it, the JSON object of one {@code DlapLogEvent} that
 * {@link DlapLogEventCodec#decodeStrict} accepts, and the router reads it as the single encoding.
 * The bytes are the real {@code StringSerializer}'s; only the broker is a stand-in.
 *
 * <p>The dormant AWS, Azure and Oracle analyzers publish a different object
 * ({@code LogEvent{payload}}) on this topic through their own template when switched on; they are
 * outside the contract and ADR-0045 removes them (log-event.md section 2).
 */
class SpineProducersWireContractTest {

    private static final String PROJECT = "dlap-demo";
    private static final Instant CRAWLED_AT = Instant.parse("2026-10-19T09:00:00Z");
    private static final CloudService GCS = new CloudService("gcs", "Google Cloud Storage", CloudProvider.GCP);
    private static final ResourceType OBJECT_TYPE = new ResourceType("gcp_gcs_object", "GCS Object", GCS);
    private static final ResourceType BUCKET_TYPE = new ResourceType("gcp_gcs_bucket", "GCS Bucket", GCS);
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    private final ObjectMapper json = new ObjectMapper();
    private final StringSerializer serializer = new StringSerializer();
    private KafkaTemplate<String, String> kafka;
    private CrawlerAssetSpineBridge bridge;
    private LogAnalyzerSpinePublisher analyzer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka)
                .send(any(String.class), any(String.class), any(String.class));
        bridge = new CrawlerAssetSpineBridge(kafka, json, new SimpleMeterRegistry());
        analyzer = new LogAnalyzerSpinePublisher(kafka, json);
    }

    private static ResourceEvent crawl(CloudResource resource, String provider, ChangeType type, String eventId) {
        ResourceEvent event = new ResourceEvent(provider, PROJECT, type, resource);
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

    /** The values sent so far, as the bytes the broker would hold. */
    private List<byte[]> wireValues() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka, org.mockito.Mockito.atLeastOnce()).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED),
                any(String.class), value.capture());
        List<byte[]> bytes = new ArrayList<>();
        for (String v : value.getAllValues()) {
            bytes.add(serializer.serialize(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, v));
        }
        return bytes;
    }

    private void assertStrictObject(byte[] wire) {
        assertThat((char) wire[0]).as("a JSON object, not a JSON string holding one").isEqualTo('{');
        DlapLogEvent event = CODEC.decodeStrict(new String(wire, StandardCharsets.UTF_8));
        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getSchemaVersion()).isEqualTo("v1");
        assertThat(event.getTargetResource().getId()).isNotBlank();
        assertThat(event.getChangeType().name()).isIn("CREATE", "UPDATE", "DELETE");
    }

    @Test
    void everyCrawlerEventShapeReachesTheBrokerAsOneStrictlyDecodableObject() {
        GcsResource bucketOnlyByName = new GcsResource(BUCKET_TYPE, "demo-bucket", "demo-bucket");
        bucketOnlyByName.setProjectId(PROJECT);
        EC2Resource vm = new EC2Resource(new ResourceType("aws_ec2_instance", "EC2",
                new CloudService("ec2", "EC2", CloudProvider.AWS)), "i-0abc123", "web");
        vm.setAccountId("123456789012");
        BigQueryResource table = new BigQueryResource(new ResourceType("gcp_bigquery_table", "Table",
                new CloudService("bigquery", "BigQuery", CloudProvider.GCP)), "dlap-demo:sales.orders", "sales.orders");
        table.setProjectId(PROJECT);
        ResourceEvent noTimestamp = crawl(object("demo-bucket", "n.csv", 1L), "GCP", ChangeType.UPDATE, "c-no-ts");
        noTimestamp.setEventTimestamp(null);

        List<ResourceEvent> events = List.of(
                crawl(object("demo-bucket", "reports/q3.csv", 2048L), "GCP", ChangeType.CREATE, "c-1"),
                crawl(object("demo-bucket", "unknown-size.bin", null), "GCP", ChangeType.UPDATE, "c-2"),
                crawl(object("demo-bucket", "_SUCCESS", 0L), "GCP", ChangeType.DELETE, "c-3"),
                crawl(bucketOnlyByName, "GCP", ChangeType.CREATE, "c-4"),
                crawl(table, "GCP", ChangeType.CREATE, "c-5"),
                crawl(vm, "AWS", ChangeType.CREATE, "c-6"),
                crawl(object("demo-bucket", "x.csv", 5L), "MYSTERY", ChangeType.CREATE, "c-7"),
                crawl(object("demo-bucket", "y.csv", 5L), "GCP", ChangeType.CREATE, null),
                noTimestamp);

        events.forEach(bridge::process);

        List<byte[]> wire = wireValues();
        assertThat(wire).hasSameSizeAs(events);
        wire.forEach(this::assertStrictObject);
        DlapLogEvent first = CODEC.decodeStrict(new String(wire.get(0), StandardCharsets.UTF_8));
        assertThat(first.getOrigin()).isEqualTo(Origin.ORIGIN_CRAWLER);
        assertThat(first.getSourceFormat()).isEqualTo("DLAP_CRAWLER/resource-event");
        assertThat(first.getTargetResource().getCanonicalUri()).isEqualTo("gs://demo-bucket/reports/q3.csv");
        assertThat(first.getTargetResource().getSizeBytes()).isEqualTo(2048L);
    }

    @Test
    void everyAnalyzerEventShapeReachesTheBrokerAsOneStrictlyDecodableObject() {
        CloudService gcs = new CloudService("gcp_gcs", "Cloud Storage", CloudProvider.GCP);
        ResourceType type = new ResourceType("gcp_gcs_bucket", "GCS Bucket", gcs);
        List<ResourceChange> changes = new ArrayList<>();
        for (ResourceChange.ChangeType changeType : List.of(ResourceChange.ChangeType.CREATE,
                ResourceChange.ChangeType.UPDATE, ResourceChange.ChangeType.DELETE)) {
            changes.add(new ResourceChange("demo-bucket", type, changeType, Instant.parse("2026-07-04T09:30:00Z"),
                    "alice@example.com"));
        }
        changes.add(new ResourceChange("bad bucket!", type, ResourceChange.ChangeType.UPDATE,
                Instant.parse("2026-07-04T09:30:00Z"), "alice@example.com"));

        changes.forEach(c -> analyzer.publish("GCP", PROJECT, c));

        List<byte[]> wire = wireValues();
        assertThat(wire).hasSameSizeAs(changes);
        wire.forEach(this::assertStrictObject);
        DlapLogEvent first = CODEC.decodeStrict(new String(wire.get(0), StandardCharsets.UTF_8));
        assertThat(first.getOrigin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        assertThat(first.getSourceFormat()).isEqualTo("DLAP_LOGANALYZER/resource-change");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRouterReadsTheBridgeOutputAsTheSingleEncodingAndForwardsIt() throws Exception {
        bridge.process(crawl(object("demo-bucket", "reports/q3.csv", 2048L), "GCP", ChangeType.CREATE, "c-1"));
        byte[] wire = wireValues().get(0);
        // The container's JSON deserializer turns the record value, a JSON object, into a Map.
        Map<String, Object> payload = json.readValue(wire, new TypeReference<>() { });
        KafkaTemplate<String, Object> typed = RouterKafkaMocks.acceptingTemplate();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(typed, json, meters);
        Acknowledgment ack = mock(Acknowledgment.class);

        router.route(payload, DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0L, ack);

        verify(ack).acknowledge();
        verify(typed).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED), eq("gs://demo-bucket/reports/q3.csv"),
                any());
        assertThat(meters.find(DiscoveryLogEventProcessor.METER_ROUTED).tag("legacy", "false").counter().count())
                .as("routed as the single encoding, not counted as legacy").isEqualTo(1.0);
        assertThat(meters.find(DiscoveryLogEventProcessor.METER_ROUTED).tag("legacy", "true").counter()).isNull();
    }

    @Test
    void aBridgeEventTheBrokerRefusesRaisesInsteadOfBeingLostInACallback() {
        doReturn(CompletableFuture.failedFuture(new NetworkException("broker is down"))).when(kafka)
                .send(any(String.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> bridge.process(crawl(object("demo-bucket", "a.csv", 1L), "GCP", ChangeType.CREATE, "c-1")))
                .isInstanceOf(SpinePublishException.class)
                .hasCauseInstanceOf(NetworkException.class);
    }

    @Test
    void anAnalyzerEventTheBrokerRefusesRaisesInsteadOfBeingLostInACallback() {
        doReturn(CompletableFuture.failedFuture(new NetworkException("broker is down"))).when(kafka)
                .send(any(String.class), any(String.class), any(String.class));
        ResourceType type = new ResourceType("gcp_gcs_bucket", "GCS Bucket",
                new CloudService("gcp_gcs", "Cloud Storage", CloudProvider.GCP));
        ResourceChange change = new ResourceChange("demo-bucket", type, ResourceChange.ChangeType.CREATE,
                Instant.parse("2026-07-04T09:30:00Z"), "alice@example.com");

        assertThatThrownBy(() -> analyzer.publish("GCP", PROJECT, change))
                .isInstanceOf(SpinePublishException.class)
                .hasCauseInstanceOf(NetworkException.class);
    }

    @Test
    void aBridgeEventThatWasNotAcknowledgedIsNotCountedAsBridged() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CrawlerAssetSpineBridge counting = new CrawlerAssetSpineBridge(kafka, json, meters);
        doReturn(CompletableFuture.failedFuture(new NetworkException("broker is down"))).when(kafka)
                .send(any(String.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> counting.process(crawl(object("demo-bucket", "a.csv", 1L), "GCP",
                ChangeType.CREATE, "c-1"))).isInstanceOf(SpinePublishException.class);

        assertThat(meters.find("dlap.discovery.assets.total").counter()).isNull();
    }
}
