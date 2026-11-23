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

package com.dlap.fixtures.gen.scale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * E2E-02: the scale generators are deterministic from a seed, produce the exact count they are asked
 * for, never repeat an event id, and lay trail files out the way the {@code S3_TRAIL_FILES} transport
 * and the {@code cloudtrail} mapping expect.
 */
class ScaleGeneratorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant END = Instant.parse("2026-11-01T12:00:00Z");
    private static final Duration SPAN = Duration.ofHours(12);
    /** The trail file name pattern of CloudTrailTrailFileTransport (da-logingest), copied for this check. */
    private static final Pattern TRAIL_FILE = Pattern.compile(
            "(\\d{12})_CloudTrail_([a-z0-9-]+)_(\\d{8}T\\d{4}Z)_[A-Za-z0-9]+\\.json(\\.gz)?");
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private static ScaleWindow window(long seed, long count) {
        return new ScaleWindow(seed, count, END, SPAN);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theSameSeedGivesTheSameBytesAndAnotherSeedDoesNot() {
        CloudTrailScale a = new CloudTrailScale(window(42, 1000), "123456789012", "us-east-1");
        CloudTrailScale b = new CloudTrailScale(window(42, 1000), "123456789012", "us-east-1");
        CloudTrailScale other = new CloudTrailScale(window(43, 1000), "123456789012", "us-east-1");

        for (long i = 0; i < 1000; i += 37) {
            assertThat(a.record(i)).isEqualTo(b.record(i));
        }
        assertThat(a.record(5)).isNotEqualTo(other.record(5));
        assertThat(a.eventId(5)).isNotEqualTo(other.eventId(5));
    }

    @Test
    void aPinnedDigestFixesTheOutputAcrossMachines() throws Exception {
        TrailFiles files = new TrailFiles(new CloudTrailScale(window(42, 2000), "123456789012", "us-east-1"), 1000);

        assertThat(sha256(files.body(0))).isEqualTo(
                "ce0aa219cb4e5f418c28376ac85656ed6e215281a0d1273c7aeeda3be7ea1f1e");
        assertThat(files.key(0)).isEqualTo("AWSLogs/123456789012/CloudTrail/us-east-1/2026/11/01/123456789012_CloudTrail_us-east-1_20261101T0600Z_73a8f29b0c899020.json.gz");
    }

    @Test
    void aRecordIsRebuiltIdenticallyInAnyOrder() {
        CloudTrailScale ct = new CloudTrailScale(window(9, 500), "123456789012", "us-east-1");
        String forward = ct.record(250);
        for (long i = 499; i >= 0; i--) {
            ct.record(i);
        }
        assertThat(ct.record(250)).isEqualTo(forward);
    }

    @Test
    void cloudTrailEventIdsAreUniqueVersion4UuidsAndEventTimesGrowInsideTheWindow() {
        long count = 50_000;
        CloudTrailScale ct = new CloudTrailScale(window(1, count), "123456789012", "us-east-1");
        Set<String> ids = new HashSet<>();
        Instant previous = Instant.MIN;
        for (long i = 0; i < count; i++) {
            String id = ct.eventId(i);
            assertThat(id).matches(UUID_V4);
            assertThat(ids.add(id)).as("event id %d is new", i).isTrue();
            Instant t = ct.window().eventTime(i);
            assertThat(t).isAfterOrEqualTo(previous).isAfterOrEqualTo(END.minus(SPAN)).isBefore(END);
            previous = t;
        }
        assertThat(ids).hasSize((int) count);
    }

    @Test
    void aCloudTrailRecordCarriesTheFieldsTheMappingReads() throws IOException {
        CloudTrailScale ct = new CloudTrailScale(window(3, 3000), "123456789012", "us-east-1");
        Set<String> operations = new HashSet<>();
        int denied = 0;
        for (long i = 0; i < 3000; i++) {
            JsonNode r = JSON.readTree(ct.record(i));
            assertThat(r.path("eventID").asText()).isEqualTo(ct.eventId(i));
            assertThat(r.path("eventSource").asText()).isEqualTo("s3.amazonaws.com");
            assertThat(r.path("eventTime").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
            assertThat(r.path("recipientAccountId").asText()).isEqualTo("123456789012");
            assertThat(r.path("awsRegion").asText()).isEqualTo("us-east-1");
            assertThat(r.at("/userIdentity/sessionContext/sessionIssuer/arn").asText())
                    .startsWith("arn:aws:iam::123456789012:role/");
            assertThat(r.at("/requestParameters/bucketName").asText()).startsWith("dlap-scale-");
            assertThat(r.at("/requestParameters/key").asText()).startsWith("raw/");
            assertThat(r.path("sourceIPAddress").asText()).startsWith("203.0.113.");
            operations.add(r.path("eventName").asText());
            if (r.has("errorCode")) {
                denied++;
            }
        }
        assertThat(operations).contains("GetObject", "PutObject", "HeadObject");
        assertThat(denied).as("about one call in 200 is denied").isBetween(3, 40);
    }

    @Test
    void trailFilesCoverEveryRecordOnceAndAreNamedLikeCloudTrail() throws IOException {
        long count = 2_500;
        CloudTrailScale ct = new CloudTrailScale(window(5, count), "123456789012", "us-east-1");
        TrailFiles files = new TrailFiles(ct, 1000);

        assertThat(files.fileCount()).isEqualTo(3);
        Set<String> keys = new HashSet<>();
        Set<String> ids = new HashSet<>();
        long records = 0;
        for (int f = 0; f < files.fileCount(); f++) {
            String key = files.key(f);
            assertThat(keys.add(key)).as("key %s is new", key).isTrue();
            String name = key.substring(key.lastIndexOf('/') + 1);
            var m = TRAIL_FILE.matcher(name);
            assertThat(m.matches()).as(name).isTrue();
            assertThat(key).startsWith("AWSLogs/123456789012/CloudTrail/us-east-1/");
            String day = key.substring("AWSLogs/123456789012/CloudTrail/us-east-1/".length(), key.lastIndexOf('/'));
            assertThat(day.replace("/", "")).isEqualTo(m.group(3).substring(0, 8));
            assertThat(files.deliveredAt(f)).isAfter(ct.window().eventTime(files.endRecord(f) - 1));

            JsonNode body;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(files.gzip(f)))) {
                body = JSON.readTree(in);
            }
            assertThat(body.path("Records").size()).isEqualTo((int) (files.endRecord(f) - files.firstRecord(f)));
            for (JsonNode r : body.path("Records")) {
                assertThat(ids.add(r.path("eventID").asText())).isTrue();
                records++;
            }
        }
        assertThat(records).isEqualTo(count);
        assertThat(ids).hasSize((int) count);
    }

    @Test
    void gcpEntriesAndTheirNormalizedFormAreCompleteAndUnique() throws Exception {
        long count = 20_000;
        GcpAuditScale gcp = new GcpAuditScale(window(11, count), "dlap-scale-gcp");
        Set<String> ids = new HashSet<>();
        int denied = 0;
        for (long i = 0; i < count; i++) {
            NormalizedEvent e = gcp.normalized(i);
            JsonNode entry = JSON.readTree(gcp.entry(i));
            assertThat(entry.path("insertId").asText()).isEqualTo(gcp.insertId(i));
            assertThat(entry.path("timestamp").asText()).isEqualTo(e.time().toString());
            assertThat(entry.at("/protoPayload/serviceName").asText()).isEqualTo("storage.googleapis.com");
            assertThat(entry.at("/protoPayload/methodName").asText()).isEqualTo(e.operation());
            assertThat(entry.at("/protoPayload/authenticationInfo/principalEmail").asText())
                    .endsWith("@dlap-scale-gcp.iam.gserviceaccount.com");

            JsonNode dto = JSON.readTree(e.toJson());
            for (String required : new String[] {"sourceProvider", "operation", "changeType", "eventTime",
                    "requestId", "targetResourceId", "rawEvent"}) {
                assertThat(dto.has(required)).as("%s of record %d", required, i).isTrue();
            }
            assertThat(dto.path("sourceProvider").asText()).isEqualTo("GCP");
            assertThat(dto.path("changeType").asText()).isIn("ACCESS", "CREATE", "UPDATE", "DELETE");
            assertThat(dto.path("targetResourceId").asText()).startsWith("gs://dlap-scale-gcp-b");
            assertThat(Instant.parse(dto.path("eventTime").asText())).isEqualTo(e.time());
            assertThat(e.eventId()).isEqualTo(NormalizedEvent.eventId("GCP", e.requestId())).hasSize(64);
            assertThat(ids.add(e.eventId())).isTrue();
            if ("FAILURE".equals(e.result())) {
                denied++;
            }
        }
        assertThat(ids).hasSize((int) count);
        assertThat(denied).isBetween(30, 300);
    }

    @Test
    void azureRecordsAndTheirNormalizedFormAreCompleteAndUnique() throws Exception {
        long count = 20_000;
        AzureActivityScale az = new AzureActivityScale(window(13, count), "6c1f0f6e-7a54-4d2b-9b0a-5d3e8f4a1c20");
        Set<String> ids = new HashSet<>();
        Set<String> changes = new HashSet<>();
        for (long i = 0; i < count; i++) {
            NormalizedEvent e = az.normalized(i);
            JsonNode raw = JSON.readTree(az.record(i));
            assertThat(raw.path("correlationId").asText()).isEqualTo(az.correlationId(i)).matches(UUID_V4);
            assertThat(raw.path("category").asText()).isEqualTo("Administrative");
            assertThat(raw.path("resourceId").asText()).startsWith("/subscriptions/6c1f0f6e-");
            JsonNode dto = JSON.readTree(e.toJson());
            assertThat(dto.path("sourceProvider").asText()).isEqualTo("AZURE");
            assertThat(dto.path("requestId").asText()).isEqualTo(az.correlationId(i));
            assertThat(dto.path("targetResourceId").asText()).contains(".blob.core.windows.net");
            assertThat(dto.path("rawEvent").path("operationName").asText()).isEqualTo(e.operation());
            assertThat(ids.add(e.eventId())).isTrue();
            changes.add(e.changeType());
        }
        assertThat(ids).hasSize((int) count);
        assertThat(changes).contains("ACCESS", "UPDATE", "PERMISSION");
    }

    @Test
    void theThreeCloudsDoNotShareIds() {
        ScaleWindow w = window(77, 100);
        String ct = new CloudTrailScale(w, "123456789012", "us-east-1").eventId(1);
        String gcp = new GcpAuditScale(w, "dlap-scale-gcp").normalized(1).eventId();
        String az = new AzureActivityScale(w, "6c1f0f6e-7a54-4d2b-9b0a-5d3e8f4a1c20").normalized(1).eventId();
        assertThat(Set.of(ct, gcp, az)).hasSize(3);
    }

    @Test
    void inputsAreValidated() {
        ScaleWindow w = window(1, 10);
        assertThatThrownBy(() -> new ScaleWindow(1, 0, END, SPAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScaleWindow(1, 1, END, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.eventTime(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudTrailScale(w, "123", "us-east-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudTrailScale(w, "123456789012", "mars")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GcpAuditScale(w, "X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AzureActivityScale(w, "not-a-guid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrailFiles(new CloudTrailScale(w, "123456789012", "us-east-1"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrailFiles(new CloudTrailScale(w, "123456789012", "us-east-1"), 5).key(2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonTextEscapesWhatMustBeEscaped() throws IOException {
        String tricky = "a\"b\\c\nd\te\u0001";
        assertThat(JSON.readTree(JsonText.quote(tricky)).asText()).isEqualTo(tricky);
    }

    @Test
    void weightedPicksFollowTheirWeights() {
        int[] weights = {900, 100};
        int first = 0;
        for (int i = 0; i < 10_000; i++) {
            if (ScaleRandom.weighted(1, i, 0, weights) == 0) {
                first++;
            }
        }
        assertThat(first).isBetween(8_700, 9_300);
        assertThat(ScaleRandom.hex(1, 1, 1, 6)).hasSize(6);
    }
}
