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

package com.dlap.catalog.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * COV-01: the catalog reads the observation da-discovery writes. The example is the same file
 * da-discovery's {@code ReadCoverageEventEncoderTest} produces ({@code coverage/meta.yaml}).
 */
class ReadCoverageWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    static String example() throws IOException {
        try (InputStream in = ReadCoverageWireTest.class.getClassLoader()
                .getResourceAsStream("coverage/observation-prefix-subset.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private static DlapLogEvent.Builder valid() throws IOException {
        return CODEC.decode(example()).toBuilder();
    }

    private static DlapLogEvent withMeta(String key, String value) throws IOException {
        DlapLogEvent.Builder b = valid();
        TargetResource.Builder t = b.getTargetResource().toBuilder();
        if (value == null) {
            t.removeTechnicalMetadata(key);
        } else {
            t.putTechnicalMetadata(key, value);
        }
        return b.setTargetResource(t).build();
    }

    @Test
    void theSharedExampleDecodes() throws IOException {
        String json = example();
        assertThat(ReadCoverageWire.mightBeObservation(json)).isTrue();
        DlapLogEvent event = CODEC.decode(json);
        assertThat(ReadCoverageWire.isObservation(event)).isTrue();

        ReadCoverageObservation o = ReadCoverageWire.decode(event, MAPPER);
        assertThat(o.provider()).isEqualTo("AWS");
        assertThat(o.scopeId()).isEqualTo("111122223333");
        assertThat(o.rootUri()).isEqualTo("s3://finance-raw");
        assertThat(o.appliesTo()).containsExactlyInAnyOrder("S3_BUCKET", "S3_OBJECT");
        assertThat(o.channel()).isEqualTo("AWS_CLOUDTRAIL");
        assertThat(o.level()).isEqualTo(ReadCoverageLevel.PARTIAL);
        assertThat(o.reasons()).containsExactly("CloudTrail data events cover a prefix subset");
        assertThat(o.observedAt()).isEqualTo(Instant.parse("2026-10-20T06:41:00Z"));
        assertThat(o.collector()).isEqualTo("aws-cloudtrail-coverage@1.0.0");
    }

    @Test
    void anOrdinaryLogEventIsNotAnObservation() throws IOException {
        DlapLogEvent read = valid().setSourceFormat("GCP_CLOUD_AUDIT/api-proto").build();
        assertThat(ReadCoverageWire.isObservation(read)).isFalse();
        assertThat(ReadCoverageWire.mightBeObservation("{\"eventId\":\"x\",\"sourceFormat\":\"GCP_CLOUD_AUDIT/api-proto\"}"))
                .isFalse();
        assertThat(ReadCoverageWire.mightBeObservation(null)).isFalse();
    }

    @Test
    void unknownIsALevelOnTheWireAndNullInTheObservation() throws IOException {
        ReadCoverageObservation o = ReadCoverageWire.decode(withMeta(ReadCoverageWire.KEY_LEVEL, "UNKNOWN"), MAPPER);
        assertThat(o.level()).isNull();
        assertThat(o.knownLevel()).isEmpty();
    }

    @Test
    void aMalformedObservationNamesTheFieldAndNeverTheValue() throws IOException {
        assertThatThrownBy(() -> ReadCoverageWire.decode(withMeta(ReadCoverageWire.KEY_LEVEL, "MOSTLY"), MAPPER))
                .isInstanceOf(InvalidReadCoverageObservationException.class)
                .hasMessage("read-coverage observation has a missing or malformed readCoverage.level");
        assertThatThrownBy(() -> ReadCoverageWire.decode(withMeta(ReadCoverageWire.KEY_REASONS, "[1]"), MAPPER))
                .hasMessageEndingWith("readCoverage.reasons");
        assertThatThrownBy(() -> ReadCoverageWire.decode(withMeta(ReadCoverageWire.KEY_SCOPE, null), MAPPER))
                .hasMessageEndingWith("readCoverage.scope");
        assertThatThrownBy(() -> ReadCoverageWire.decode(withMeta(ReadCoverageWire.KEY_CHANNEL, "aws trail"), MAPPER))
                .hasMessageEndingWith("readCoverage.channel");
        assertThatThrownBy(() -> ReadCoverageWire.decode(valid().clearEventTime().build(), MAPPER))
                .hasMessageEndingWith("event_time");
    }

    @Test
    void anObservationMayNameOnlyItsOwnProvidersKindsUnderAnAcceptableRoot() throws IOException {
        assertThatThrownBy(() -> ReadCoverageWire.decode(withMeta(ReadCoverageWire.KEY_APPLIES_TO, "S3_BUCKET,GCS_BUCKET"),
                MAPPER)).hasMessageEndingWith("readCoverage.appliesTo");
        assertThatThrownBy(() -> ReadCoverageWire.decode(valid().setSourceProvider(SourceProvider.GCP).build(), MAPPER))
                .as("an s3:// root from a GCP observation").hasMessageEndingWith("target_resource.id");
        for (String root : List.of("s3://", "s3://bucket/key", "s3://a b", "gs://", "file:///etc", "bq://p/d/t")) {
            DlapLogEvent.Builder b = valid();
            b.setTargetResource(b.getTargetResource().toBuilder().setId(root));
            assertThatThrownBy(() -> ReadCoverageWire.decode(b.build(), MAPPER)).as(root)
                    .hasMessageEndingWith("target_resource.id");
        }
        assertThat(ReadCoverageWire.scheme("bq://example.com:proj/ds")).contains("bq://");
        assertThat(ReadCoverageWire.scheme("az://dlapdemolake")).contains("az://");
    }

    @Test
    void reasonsAreBoundedInCountAndLength() throws IOException {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("gap " + i);
        }
        many.add("x".repeat(900));
        ReadCoverageObservation o = ReadCoverageWire.decode(
                withMeta(ReadCoverageWire.KEY_REASONS, MAPPER.writeValueAsString(many)), MAPPER);
        assertThat(o.reasons()).hasSize(ReadCoverageWire.MAX_REASONS);
        assertThat(o.reasons().get(ReadCoverageWire.MAX_REASONS - 1)).isEqualTo("12 more reasons not shown");

        ReadCoverageObservation longOne = ReadCoverageWire.decode(
                withMeta(ReadCoverageWire.KEY_REASONS, MAPPER.writeValueAsString(List.of("y".repeat(900)))), MAPPER);
        assertThat(longOne.reasons().get(0)).hasSize(ReadCoverageWire.MAX_REASON_LENGTH);
    }
}
