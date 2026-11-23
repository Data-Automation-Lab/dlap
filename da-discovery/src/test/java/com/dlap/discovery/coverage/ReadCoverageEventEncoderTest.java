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

package com.dlap.discovery.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.SourceProvider;

/**
 * COV-01: the wire form of an observation on the log spine. The example in
 * {@code docs/specs/asset-descriptor.md} ("Read coverage") is this test's first case; da-catalog's
 * {@code ReadCoverageWireTest} decodes the same JSON.
 */
class ReadCoverageEventEncoderTest {

    static final Instant AT = Instant.parse("2026-10-20T06:41:00Z");

    private final ReadCoverageEventEncoder encoder = new ReadCoverageEventEncoder(CoverageFixtures.MAPPER);
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    private static ReadCoverageObservation prefixSubset() {
        return new ReadCoverageObservation("AWS", "111122223333", "s3://finance-raw", List.of("S3_BUCKET", "S3_OBJECT"),
                "AWS_CLOUDTRAIL", CoverageVerdict.partial(List.of("CloudTrail data events cover a prefix subset")),
                "aws-cloudtrail-coverage@1.0.0");
    }

    @Test
    void theEncodedEventIsTheSpecExampleAndPassesTheStrictDecoder() {
        String json = codec.encode(encoder.toEvent(prefixSubset(), AT));
        DlapLogEvent strict = codec.decodeStrict(json);

        String key = "DLAP_READ_COVERAGE|s3://finance-raw|2026-10-20T06:41:00Z";
        assertThat(strict.getEventId()).isEqualTo(LogEventIds.eventId(SourceProvider.AWS, key));
        assertThat(strict.getNativeEventId()).isEqualTo(key);
        assertThat(strict.getSourceFormat()).isEqualTo("DLAP_READ_COVERAGE/config-observation");
        assertThat(strict.getOrigin()).isEqualTo(Origin.ORIGIN_CRAWLER);
        assertThat(strict.getTargetResource().getId()).isEqualTo("s3://finance-raw");
        assertThat(strict.getTargetResource().getKind()).isEqualTo("DLAP_READ_COVERAGE");
        assertThat(strict.getTargetResource().getTechnicalMetadataMap())
                .containsEntry("readCoverage.level", "PARTIAL")
                .containsEntry("readCoverage.reasons", "[\"CloudTrail data events cover a prefix subset\"]")
                .containsEntry("readCoverage.appliesTo", "S3_BUCKET,S3_OBJECT")
                .containsEntry("readCoverage.channel", "AWS_CLOUDTRAIL")
                .containsEntry("readCoverage.scope", "111122223333");
        assertThat(strict.getParserVersion()).isEqualTo("aws-cloudtrail-coverage@1.0.0");
        assertThat(strict.getEventTime().getSeconds()).isEqualTo(AT.getEpochSecond());

        assertThat(json).as("the shared wire example that da-catalog's ReadCoverageWireTest decodes")
                .isEqualTo(golden());
    }

    private static String golden() {
        try (java.io.InputStream in = ReadCoverageEventEncoderTest.class.getClassLoader()
                .getResourceAsStream("coverage/wire/observation-prefix-subset.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void anObservationNeverChangesLifecycleStateOrCountsAsARead() {
        DlapLogEvent event = encoder.toEvent(prefixSubset(), AT);
        assertThat(event.getChangeType()).isEqualTo(ChangeType.CHANGE_TYPE_UNSPECIFIED);
        assertThat(LogEventSemantics.mayChangeState(event)).isFalse();
        assertThat(LogEventSemantics.isSuccessfulRead(event)).isFalse();
    }

    @Test
    void anUnknownVerdictIsSentAsUnknownWithItsReason() {
        ReadCoverageObservation o = new ReadCoverageObservation("GCP", "dlap-demo-data", "bq://dlap-demo-data",
                List.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"), "GCP_CLOUD_AUDIT",
                CoverageVerdict.unknown(new MissingPermissionException("resourcemanager.projects.getIamPolicy",
                        "projects/dlap-demo-data")), "gcp-audit-config-coverage@1.0.0");
        DlapLogEvent e = codec.decodeStrict(codec.encode(encoder.toEvent(o, AT)));
        assertThat(e.getTargetResource().getTechnicalMetadataMap())
                .containsEntry("readCoverage.level", "UNKNOWN")
                .containsEntry("readCoverage.reasons",
                        "[\"missing permission resourcemanager.projects.getIamPolicy on projects/dlap-demo-data\"]");
        assertThat(e.getSourceProvider()).isEqualTo(SourceProvider.GCP);
    }

    @Test
    void theSameObservationAtTheSameTimeHasTheSameId() {
        assertThat(encoder.toEvent(prefixSubset(), AT).getEventId())
                .isEqualTo(encoder.toEvent(prefixSubset(), AT).getEventId())
                .isNotEqualTo(encoder.toEvent(prefixSubset(), AT.plusSeconds(1)).getEventId());
    }
}
