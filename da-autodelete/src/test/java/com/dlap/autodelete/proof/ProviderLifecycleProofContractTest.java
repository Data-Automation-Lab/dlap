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

package com.dlap.autodelete.proof;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.PrincipalType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PARSE-02 (Q-612): the contract between da-logingest's parsers and {@link DeletionProofMatcher},
 * pinned by the shared fixtures. The events are the {@code expected.json} events of the
 * da-log-fixtures cases that da-logingest's contract tests hold the CloudTrail, S3 server access log
 * and Cloud Audit parsers to, completed with the event id formula and the streamed origin every
 * published event carries ({@code ProviderLifecycleDeletePublishTest} in da-logingest). The shared
 * fields are change type, result, provider, canonical URI, event time, operation, source service and
 * principal type; if a parser stops emitting any of them, its contract test fails, and if the matcher
 * stops accepting them, this test fails. PARSE-03 (D36) adds the events that must prove nothing: an
 * unlisted S3 lifecycle event naming only a bucket (Q-650), version-qualified deletes and delete
 * markers (Q-651), and another AWS service's delete under a lifecycle rule (Q-654).
 */
class ProviderLifecycleProofContractTest {

    private static final Path CORPUS = Path.of("..", "da-log-fixtures", "src", "main", "resources", "dlap-fixtures");
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SKEW = Duration.ofMinutes(10);

    /** The rule was applied a day before the fixture events. */
    private static final Instant APPLIED = Instant.parse("2026-09-01T00:00:00Z");

    private static List<DlapLogEvent> wireEvents(String caseDir) throws IOException {
        JsonNode expected = JSON.readTree(Files.readString(CORPUS.resolve(caseDir).resolve("expected.json")));
        List<DlapLogEvent> out = new ArrayList<>();
        for (JsonNode e : expected.path("events")) {
            DlapLogEvent partial = CODEC.decode(JSON.writeValueAsString(e));
            out.add(partial.toBuilder()
                    .setEventId(LogEventIds.eventId(partial.getSourceProvider(), partial.getNativeEventId()))
                    .setOrigin(Origin.ORIGIN_CLOUD_LOG)
                    .build());
        }
        return out;
    }

    private static List<DlapLogEvent> deletes(String caseDir) throws IOException {
        return wireEvents(caseDir).stream().filter(e -> e.getChangeType() == ChangeType.DELETE).toList();
    }

    private static RetireDispositionEntity rule(String provider, String platform, String target) {
        return RetireDispositionEntity.builder().id(UUID.randomUUID()).softDeleteRecordId(UUID.randomUUID())
                .cloudProvider(provider).mode(RetireDispositionEntity.MODE_NATIVE_RULE).platform(platform)
                .targetUri(target).appliedAt(APPLIED).status(RetireDispositionEntity.AWAITING_PROOF).build();
    }

    private static RetireDispositionEntity engineDelete(String provider, String target) {
        return RetireDispositionEntity.builder().id(UUID.randomUUID()).softDeleteRecordId(UUID.randomUUID())
                .cloudProvider(provider).mode("DIRECT").platform("ENGINE_" + provider).targetUri(target)
                .appliedAt(APPLIED).status(RetireDispositionEntity.AWAITING_PROOF).build();
    }

    @Test
    void anS3LifecycleCloudTrailExpirationProvesTheBucketsLifecycleRule() throws IOException {
        List<DlapLogEvent> deletes = deletes("cloudtrail/s3-file/1.11/s3-lifecycle-expiration-and-transition");

        assertThat(deletes).hasSize(1);
        DlapLogEvent e = deletes.get(0);
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(DeletionProofMatcher.match(e, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e"), SKEW))
                .contains("AWS_CLOUDTRAIL/s3-file");
        assertThat(DeletionProofMatcher.match(e, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e-other"), SKEW))
                .as("another bucket's rule").isEmpty();
    }

    @Test
    void anS3ServerAccessLogExpirationProvesItsBucketsLifecycleRuleAndItsDeleteMarkerDoesNot() throws IOException {
        List<DlapLogEvent> deletes = deletes("s3accesslog/cwl-json/V_1_0/lifecycle-expiration");

        // PARSE-03 (D36, Q-651): the delete marker keeps every version, so it is an UPDATE and proves nothing.
        assertThat(deletes).hasSize(1);
        assertThat(DeletionProofMatcher.match(deletes.get(0), rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e"), SKEW))
                .contains("AWS_S3_SERVER_ACCESS_LOG/cwl-json");
        assertThat(deletes.get(0).getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        DlapLogEvent marker = wireEvents("s3accesslog/cwl-json/V_1_0/lifecycle-expiration").get(1);
        assertThat(marker.getOperation()).isEqualTo("S3.CREATE.DELETEMARKER");
        assertThat(DeletionProofMatcher.match(marker, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e-versioned/raw"),
                SKEW)).isEmpty();
    }

    @Test
    void anUnlistedS3LifecycleEventThatNamesOnlyABucketProvesNoRule() throws IOException {
        // Q-650: before PARSE-03 the verb heuristics made DeleteMarkerCreated a DELETE of s3://dlap-aws-e2e.
        List<DlapLogEvent> events = wireEvents("cloudtrail/s3-file/1.11/s3-lifecycle-unlisted-events");
        DlapLogEvent bucketOnly = events.get(0);

        assertThat(bucketOnly.getSourceService()).isEqualTo("s3lifecycle.amazonaws.com");
        assertThat(bucketOnly.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(bucketOnly.getTargetResource().getCanonicalUri()).isEmpty();
        assertThat(DeletionProofMatcher.match(bucketOnly, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e"), SKEW)).isEmpty();
        assertThat(DeletionProofMatcher.match(bucketOnly, engineDelete("AWS", "s3://dlap-aws-e2e"), SKEW)).isEmpty();
        for (String c : List.of("cloudtrail/s3-file/1.11/s3-lifecycle-unlisted-events",
                "cloudtrail/s3-file/1.11/s3-lifecycle-unlisted-heuristic")) {
            assertThat(deletes(c)).as(c).isEmpty();
            for (DlapLogEvent e : wireEvents(c)) {
                assertThat(DeletionProofMatcher.match(e, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e"), SKEW)).isEmpty();
                assertThat(DeletionProofMatcher.match(e, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e-versioned"),
                        SKEW)).isEmpty();
            }
        }
    }

    @Test
    void versionQualifiedDeletesProveNoDispositionButTheCurrentObjectDeleteDoes() throws IOException {
        // Q-651: a version delete, a delete marker and a noncurrent expiration leave the object in place.
        List<DlapLogEvent> trail = wireEvents("cloudtrail/s3-file/1.11/s3-versioned-deletes");
        List<DlapLogEvent> accessLog = wireEvents("s3accesslog/cwl-json/V_1_0/versioned-deletes");
        assertThat(deletes("s3accesslog/cwl-json/V_1_0/versioned-deletes")).isEmpty();
        for (DlapLogEvent e : accessLog) {
            assertThat(e.getChangeType()).isEqualTo(ChangeType.UPDATE);
            String uri = e.getTargetResource().getCanonicalUri();
            assertThat(DeletionProofMatcher.match(e, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e-versioned"), SKEW))
                    .as(e.getOperation()).isEmpty();
            assertThat(DeletionProofMatcher.match(e, engineDelete("AWS", uri), SKEW)).as(e.getOperation()).isEmpty();
        }
        for (DlapLogEvent e : trail.subList(0, 3)) {
            assertThat(e.getChangeType()).isEqualTo(ChangeType.UPDATE);
            assertThat(DeletionProofMatcher.match(e, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e-versioned"), SKEW))
                    .isEmpty();
            assertThat(DeletionProofMatcher.match(e, engineDelete("AWS", e.getTargetResource().getCanonicalUri()), SKEW))
                    .isEmpty();
        }
        DlapLogEvent current = trail.get(3);
        assertThat(current.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(DeletionProofMatcher.match(current, engineDelete("AWS", "s3://dlap-aws-e2e/curated/2026/old.parquet"),
                SKEW)).contains("AWS_CLOUDTRAIL/s3-file");
    }

    @Test
    void anotherAwsServicesDeleteProvesAnEngineDeleteButNoLifecycleRule() throws IOException {
        // Q-654: SYSTEM, but not the S3 lifecycle signature.
        DlapLogEvent e = deletes("cloudtrail/s3-file/1.11/s3-aws-service-delete-not-lifecycle").get(0);

        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(DeletionProofMatcher.match(e, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e-replica"), SKEW)).isEmpty();
        assertThat(DeletionProofMatcher.match(e, engineDelete("AWS", "s3://dlap-aws-e2e-replica/raw/2026/09/orders.parquet"),
                SKEW)).isPresent();
    }

    @Test
    void aBigQueryInternalTableExpiredProvesTheTablesExpiration() throws IOException {
        for (String c : List.of("gcpaudit/json/bq-auditmetadata/table-expired",
                "gcpaudit/api-proto/bq-auditmetadata/table-expired")) {
            List<DlapLogEvent> deletes = deletes(c);
            assertThat(deletes).as(c).hasSize(1);
            DlapLogEvent e = deletes.get(0);
            assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
            assertThat(DeletionProofMatcher.match(e, rule("GCP", "BIGQUERY_TABLE_EXPIRATION",
                    "bq://proj-0d1a0001/sales/tmp_orders_20260801"), SKEW)).as(c).isPresent();
            assertThat(DeletionProofMatcher.match(e, rule("GCP", "BIGQUERY_TABLE_EXPIRATION",
                    "bq://proj-0d1a0001/sales/orders"), SKEW)).as("another table").isEmpty();
        }
    }

    @Test
    void aPersonsDeleteInTheAccessLogIsNoLifecycleProofButProvesAnEngineDelete() throws IOException {
        DlapLogEvent person = deletes("s3accesslog/cwl-json/V_1_0/object-and-bucket-requests").get(0);

        assertThat(person.getOperation()).isEqualTo("REST.DELETE.OBJECT");
        assertThat(person.getPrincipal().getType()).isEqualTo(PrincipalType.USER);
        assertThat(DeletionProofMatcher.match(person, rule("AWS", "S3_LIFECYCLE", "s3://dlap-aws-e2e"), SKEW)).isEmpty();
        assertThat(DeletionProofMatcher.match(person, engineDelete("AWS", "s3://dlap-aws-e2e/curated/2026/old.parquet"),
                SKEW)).isPresent();
    }
}
