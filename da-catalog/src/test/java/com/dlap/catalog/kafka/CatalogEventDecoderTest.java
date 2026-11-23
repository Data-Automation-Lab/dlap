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

package com.dlap.catalog.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventDecodeException;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.Timestamps;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Dual read through the codec, target normalization with hints, and the dedup keys and
 * evidence channel the catalog derives once per event.
 */
class CatalogEventDecoderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CatalogIngestProperties properties = new CatalogIngestProperties();
    private final CatalogEventDecoder decoder = new CatalogEventDecoder(properties, new CatalogIngestMetrics(registry));

    private static DlapLogEvent.Builder read(String target) {
        return DlapLogEvent.newBuilder()
                .setEventId("e-1")
                .setEventTime(Timestamps.parseUnchecked("2026-10-01T10:00:00Z"))
                .setSourceProvider(SourceProvider.GCP)
                .setChangeType(ChangeType.ACCESS)
                .setResultStatus(ResultStatus.SUCCESS)
                .setTargetResource(TargetResource.newBuilder().setId(target));
    }

    @Test
    void readsTheSingleAndTheLegacyDoubleEncodingAlike() throws Exception {
        String single = DlapLogEventCodec.defaults().encode(read("projects/_/buckets/some-bucket").build());
        String doubled = new ObjectMapper().writeValueAsString(single);

        CatalogEvent a = decoder.decode(single, "test");
        CatalogEvent b = decoder.decode(doubled, "test");

        assertThat(a.event()).isEqualTo(b.event());
        assertThat(a.isLegacy()).isFalse();
        assertThat(b.isLegacy()).isTrue();
        assertThat(registry.get(CatalogIngestMetrics.DECODED).tag("legacy", "true").counter().count()).isEqualTo(1.0);
    }

    @Test
    void readsTheLegacyCrawlerBridgeShape() {
        String crawler = """
            {"eventId":"s3://crawled-bucket:CREATE:2026-10-01T10:00:00Z","changeType":"CREATE","source":"crawler",
             "operation":"DISCOVERY","sourceProvider":"AWS","eventTime":"2026-10-01T10:00:00Z",
             "targetResource":{"id":"s3://crawled-bucket","name":"crawled-bucket","kind":"aws_s3_bucket","region":"us-east-1",
             "technicalMetadata":{"versioning":"Enabled"}}}
            """;

        CatalogEvent e = decoder.decode(crawler, "test");

        assertThat(e.isCrawler()).isTrue();
        assertThat(e.isProven()).as("a crawler observation without a result status proves existence").isTrue();
        assertThat(e.channel()).isEqualTo(CatalogEvent.CHANNEL_CRAWLER);
        assertThat(e.category()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(e.canonicalTarget()).get().satisfies(id -> {
            assertThat(id.uri()).isEqualTo("s3://crawled-bucket");
            assertThat(id.kind()).isEqualTo(AssetKind.S3_BUCKET);
        });
        assertThat(e.technicalMetadata()).containsEntry("versioning", "Enabled");
    }

    @Test
    void readsTheLegacyFlatTargetKeys() {
        String flat = """
            {"eventId":"x","changeType":"ACCESS","resultStatus":"SUCCESS","eventTime":"2026-10-01T10:00:00Z",
             "cloudProvider":"gcp","targetResourceId":"projects/_/buckets/flat-bucket"}
            """;

        CatalogEvent e = decoder.decode(flat, "test");

        assertThat(e.rawTargetId()).isEqualTo("projects/_/buckets/flat-bucket");
        assertThat(e.canonicalTarget()).get().extracting(id -> id.uri()).isEqualTo("gs://flat-bucket");
    }

    @Test
    void rejectsAPayloadThatIsNotALogEvent() {
        assertThatThrownBy(() -> decoder.decode("not json", "test")).isInstanceOf(LogEventDecodeException.class);
        assertThatThrownBy(() -> decoder.decode("[1,2]", "test")).isInstanceOf(LogEventDecodeException.class);
    }

    @Test
    void prefersTheCanonicalUriAndFallsBackToTheIdWhenItDoesNotNormalize() {
        DlapLogEvent withCanonical = read("projects/_/buckets/raw-form").setTargetResource(TargetResource.newBuilder()
                .setId("projects/_/buckets/raw-form").setCanonicalUri("gs://raw-form")).build();
        DlapLogEvent badCanonical = read("projects/_/buckets/raw-form").setTargetResource(TargetResource.newBuilder()
                .setId("projects/_/buckets/raw-form").setCanonicalUri("nonsense")).build();

        assertThat(decoder.decode(withCanonical, "t").canonicalTarget()).get()
                .extracting(id -> id.uri()).isEqualTo("gs://raw-form");
        assertThat(decoder.decode(badCanonical, "t").canonicalTarget()).get()
                .extracting(id -> id.uri()).isEqualTo("gs://raw-form");
    }

    @Test
    void cloudTrailResourceTypeIsAKindHintForBareNames() {
        DlapLogEvent bare = read("dlap-logs").setSourceProvider(SourceProvider.AWS)
                .setTargetResource(TargetResource.newBuilder().setId("dlap-logs").setKind("AWS::S3::Bucket")).build();
        DlapLogEvent noKind = read("dlap-logs").setSourceProvider(SourceProvider.AWS).build();

        assertThat(decoder.decode(bare, "t").canonicalTarget()).get().extracting(id -> id.uri())
                .isEqualTo("s3://dlap-logs");
        assertThat(decoder.decode(noKind, "t").target()).isInstanceOf(AssetIdParseResult.Rejected.class);
    }

    @Test
    void thereIsNoProviderDefault() {
        DlapLogEvent unknown = read("cold-backups").setSourceProvider(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED)
                .build();

        CatalogEvent e = decoder.decode(unknown, "t");

        assertThat(e.canonicalTarget()).isEmpty();
        assertThat(e.providerTag()).isEqualTo("UNKNOWN");
    }

    @Test
    void defaultHintsResolveStoredFormsThatNeedContext() {
        assertThat(decoder.normalizeStored("main.sales.orders", "UNITY_TABLE", "UNITY", null).isNormalized())
                .as("a three-part name needs the metastore").isFalse();

        properties.getMatch().getDefaultHints().setMetastoreId("ms-1");

        assertThat(decoder.normalizeStored("main.sales.orders", "UNITY_TABLE", "UNITY", null).id()).get()
                .extracting(id -> id.uri()).isEqualTo("uc://ms-1/main/sales/orders");
        assertThat(decoder.normalizeStored("plain-bucket", "GCS_BUCKET", "GCP", null).id()).get()
                .extracting(id -> id.uri()).isEqualTo("gs://plain-bucket");
        assertThat(decoder.normalizeStored("proj-one:ds.tbl", "BIGQUERY_TABLE", "GCP", null).id()).get()
                .extracting(id -> id.uri()).isEqualTo("bq://proj-one/ds/tbl");
    }

    @Test
    void usageKeyUsesTheSharedEventIdAcrossAccountsAndTheSupersededIdForCorrections() {
        DlapLogEvent requester = read("s3://shared-b/k").setEventId("e-req").setSourceProvider(SourceProvider.AWS)
                .setSharedEventId("shared-1").build();
        DlapLogEvent owner = requester.toBuilder().setEventId("e-own").build();
        DlapLogEvent correction = read("s3://shared-b/k").setEventId("e-fix").setSupersedesEventId("e-orig").build();

        CatalogEvent a = decoder.decode(requester, "t");
        CatalogEvent b = decoder.decode(owner, "t");

        assertThat(a.eventKey()).isNotEqualTo(b.eventKey());
        assertThat(a.usageKey()).isEqualTo(b.usageKey()).isEqualTo(LogEventIds.usageDedupKey(requester));
        assertThat(decoder.decode(correction, "t").usageKey()).isEqualTo("e-orig");
        assertThat(decoder.decode(correction, "t").supersedes()).contains("e-orig");
    }

    @Test
    void anEventWithoutAnIdGetsAStableKeyFromItsContent() {
        DlapLogEvent noId = read("gs://some-bucket").setEventId("").build();

        String k1 = decoder.decode(noId, "t").eventKey();
        String k2 = decoder.decode(DlapLogEventCodec.defaults().encode(noId), "t").eventKey();

        assertThat(k1).startsWith("payload:").isEqualTo(k2);
    }

    @Test
    void channelNamesTheSourceKind() {
        DlapLogEvent registry = read("gs://some-bucket").setSourceFormat("GCP_CLOUD_AUDIT/api-proto")
                .setOrigin(Origin.ORIGIN_CLOUD_LOG).build();
        DlapLogEvent observed = read("gs://some-bucket").setSourceFormat("AWS_S3_INVENTORY/csv")
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED).build();
        DlapLogEvent legacy = read("gs://some-bucket").build();

        assertThat(decoder.decode(registry, "t").channel()).isEqualTo("AUDIT_LOG:GCP_CLOUD_AUDIT");
        assertThat(decoder.decode(observed, "t").channel()).isEqualTo("PROVIDER_METADATA:AWS_S3_INVENTORY");
        assertThat(decoder.decode(legacy, "t").channel()).isEqualTo("AUDIT_LOG");
    }

    @Test
    void failedEventsProveNothingAndMissingTimeCannotChangeState() {
        DlapLogEvent failed = read("gs://some-bucket").setResultStatus(ResultStatus.FAILURE).build();
        DlapLogEvent untimed = read("gs://some-bucket").clearEventTime().build();

        assertThat(decoder.decode(failed, "t").isProven()).isFalse();
        assertThat(decoder.decode(untimed, "t").mayChangeState()).isFalse();
        assertThat(decoder.decode(read("gs://some-bucket").build(), "t").eventTime())
                .contains(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void sizeHasExplicitPresence() {
        DlapLogEvent unknown = read("gs://some-bucket").build();
        DlapLogEvent zero = read("gs://some-bucket").setTargetResource(TargetResource.newBuilder().setId("gs://some-bucket")
                .setSizeBytes(0L)).build();

        assertThat(decoder.decode(unknown, "t").sizeBytes()).isEmpty();
        assertThat(decoder.decode(zero, "t").sizeBytes()).contains(0L);
    }
}
