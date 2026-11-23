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

package com.dlap.logingest.parse.scale;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.fixtures.gen.scale.AzureActivityScale;
import com.dlap.fixtures.gen.scale.CloudTrailScale;
import com.dlap.fixtures.gen.scale.GcpAuditScale;
import com.dlap.fixtures.gen.scale.NormalizedEvent;
import com.dlap.fixtures.gen.scale.ScaleWindow;
import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.parse.cloudtrail.CloudTrailParser;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedParser;
import com.dlap.logingest.parse.gcpaudit.GcpAuditFormat;
import com.dlap.logingest.parse.gcpaudit.GcpAuditRegistryForTests;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.ResultStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E2E-02: what the scale generators produce is what the log layer reads. The records of every cloud go
 * through the real parsers (CloudTrail and GCP audit as raw records, the three clouds as normalized
 * records), and none is quarantined or skipped, so a quarantine rate measured at scale is the pipeline's
 * and not the generator's. Event ids are unique and, for the normalized path, equal to the ids the
 * generator predicts, which is what the backfill resume check compares by.
 */
class ScaleRecordsParseTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-01T12:00:00Z");
    private static final Duration SPAN = Duration.ofHours(12);
    private static final int COUNT = 4_000;
    private static final CanonicalEventMapper MAPPER = new CanonicalEventMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    private static ScaleWindow window(long seed) {
        return new ScaleWindow(seed, COUNT, END, SPAN);
    }

    @Test
    void everyGeneratedCloudTrailRecordParsesToOneEventWithItsNativeId() {
        MappingSpec mapping = AbstractParserContractTest.classpathMapping(CloudTrailFormat.FAMILY);
        DefaultParserRegistry registry = AbstractParserContractTest.registryOf(mapping, CloudTrailParser.forSpec(mapping));
        BindingConfig binding = mapping.defaultBinding("ct-scale");
        CloudTrailScale ct = new CloudTrailScale(window(42), "123456789012", "us-east-1");
        Set<String> ids = new HashSet<>();
        int failures = 0;

        for (long i = 0; i < COUNT; i++) {
            RawRecord raw = RawRecord.builder().bindingId(binding.bindingId()).sourceKind(CloudTrailFormat.SOURCE_KIND)
                    .envelope(CloudTrailFormat.S3_FILE).ref(RawRef.of("TEST", "test:scale", (int) i))
                    .mode(IngestMode.STREAM).fetchedAt(NOW).text(ct.record(i)).build();
            ParseOutcome outcome = registry.parse(raw, binding);
            List<DlapLogEvent> events = MAPPER.events(outcome);
            assertThat(events).as("record %d: %s", i, outcome.outcomeCode()).hasSize(1);
            DlapLogEvent e = events.get(0);
            assertThat(e.getNativeEventId()).isEqualTo(ct.eventId(i));
            assertThat(e.getTargetResource().getCanonicalUri()).startsWith("s3://" + ct.bucket(i));
            assertThat(e.getEventTime().getSeconds()).isEqualTo(ct.window().eventTime(i).getEpochSecond());
            assertThat(ids.add(e.getEventId())).as("event id of record %d is new", i).isTrue();
            if (e.getResultStatus() == ResultStatus.FAILURE) {
                failures++;
            }
            if (e.getChangeType() == ChangeType.DELETE) {
                assertThat(e.getTargetResource().getCanonicalUri()).contains("/raw/");
            }
        }
        assertThat(ids).hasSize(COUNT);
        assertThat(failures).as("about one call in 200 is denied").isBetween(5, 60);
    }

    @Test
    void everyGeneratedGcpEntryParsesAsARawAuditRecord() {
        DefaultParserRegistry registry = GcpAuditRegistryForTests.registry();
        MappingSpec mapping = AbstractParserContractTest.classpathMapping(GcpAuditFormat.FAMILY);
        BindingConfig binding = mapping.defaultBinding("gcp-scale");
        GcpAuditScale gcp = new GcpAuditScale(window(11), "dlap-scale-gcp");
        Set<String> ids = new HashSet<>();

        for (long i = 0; i < COUNT; i++) {
            RawRecord raw = RawRecord.builder().bindingId(binding.bindingId()).sourceKind(GcpAuditFormat.SOURCE_KIND)
                    .envelope(GcpAuditFormat.JSON).ref(RawRef.of("TEST", "test:scale", (int) i))
                    .mode(IngestMode.STREAM).fetchedAt(NOW).text(gcp.entry(i)).build();
            ParseOutcome outcome = registry.parse(raw, binding);
            List<DlapLogEvent> events = MAPPER.events(outcome);
            assertThat(events).as("entry %d: %s", i, outcome.outcomeCode()).hasSize(1);
            assertThat(ids.add(events.get(0).getEventId())).isTrue();
        }
        assertThat(ids).hasSize(COUNT);
    }

    @Test
    void theNormalizedFormOfEveryCloudParsesWithTheIdTheGeneratorPredicts() {
        DefaultParserRegistry registry = new DefaultParserRegistry(
                DlapNormalizedFormat.providers().stream().map(DlapNormalizedParser::new).toList(),
                DlapNormalizedFormat.providers().stream().map(p -> (VersionDetector) new DlapNormalizedDetector(p)).toList());
        GcpAuditScale gcp = new GcpAuditScale(window(21), "dlap-scale-gcp");
        AzureActivityScale az = new AzureActivityScale(window(22), "6c1f0f6e-7a54-4d2b-9b0a-5d3e8f4a1c20");

        for (long i = 0; i < COUNT; i++) {
            checkNormalized(registry, gcp.normalized(i), "DLAP_NORMALIZED_GCP", "dlap-replay-gcp", i);
            checkNormalized(registry, az.normalized(i), "DLAP_NORMALIZED_AZURE", "dlap-replay-azure", i);
        }
    }

    private static void checkNormalized(DefaultParserRegistry registry, NormalizedEvent n, String sourceKind,
                                        String bindingId, long index) {
        RawRecord raw = RawRecord.builder().bindingId(bindingId).sourceKind(sourceKind).envelope("ingest-dto")
                .ref(RawRef.of("FIXTURE_REPLAY", "fixture:scale", (int) index)).mode(IngestMode.REPLAY).fetchedAt(NOW)
                .text(n.toJson()).build();
        ParseOutcome outcome = registry.parse(raw, BindingConfig.auto(bindingId, sourceKind));
        List<DlapLogEvent> events = MAPPER.events(outcome);
        assertThat(events).as("%s record %d: %s", n.provider(), index, outcome.outcomeCode()).hasSize(1);
        assertThat(events.get(0).getEventId()).isEqualTo(n.eventId());
        assertThat(events.get(0).getNativeEventId()).isEqualTo(n.requestId());
    }
}
