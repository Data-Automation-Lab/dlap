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

package com.dlap.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The record form, the canonical JSON and the hash input of docs/specs/audit-log.md. The expected
 * hashes were computed outside Java ({@code sha256sum} over the hash input bytes), and
 * {@link AuditLogSchemaContainerTest} checks that {@code dlap_common.audit_entry_hash} in
 * PostgreSQL 15.1 gives the same values, so the test pins the format, not just this implementation.
 */
class AuditChainTest {

    /** The spec's worked example: the record of {@link #exampleEvent()} written at 08:15:31. */
    static final String EXAMPLE_RECORD = "{\"actor\":{\"id\":\"5f0c6f3e-1111-4a1b-9c3d-000000000001\",\"kind\":\"USER\"},"
            + "\"correlationId\":\"appr-7\",\"details\":{\"bytes\":1024,\"dryRun\":false,\"note\":\"caf\\u00e9 \\\"x\\\"\\n\"},"
            + "\"eventType\":\"retire.delete.executed\",\"occurredAt\":\"2026-10-21T08:15:30.123456Z\","
            + "\"outcome\":\"SUCCEEDED\",\"recordedAt\":\"2026-10-21T08:15:31.000000Z\",\"schema\":\"dlap-audit-record/1\","
            + "\"target\":{\"id\":\"policy-42\",\"kind\":\"POLICY\"}}";

    /** SHA-256 of the hash input of the example as seq 1 of retire.delete. */
    static final String EXAMPLE_HASH_1 = "a698fa7c1879e091ad33b5288f719beb95e1b7bea11d788d769842830660f7ca";

    /** The same record as seq 2, chained to {@link #EXAMPLE_HASH_1}. */
    static final String EXAMPLE_HASH_2 = "7095302b7951f5b8d937b32ac7693cf0398ca22ecef2639927362e18a00701ee";

    static AuditEvent exampleEvent() {
        return AuditEvent.builder("retire.delete.executed")
                .occurredAt(Instant.parse("2026-10-21T08:15:30.123456789Z"))
                .actor(AuditActor.user("5f0c6f3e-1111-4a1b-9c3d-000000000001"))
                .target(AuditTarget.of("POLICY", "policy-42"))
                .outcome(AuditOutcome.SUCCEEDED)
                .correlationId("appr-7")
                .detail("note", "caf\u00e9 \"x\"\n")
                .detail("dryRun", false)
                .detail("bytes", 1024)
                .build();
    }

    @Test
    void theRecordOfTheSpecExampleIsExact() {
        assertThat(AuditChain.record(exampleEvent(), Instant.parse("2026-10-21T08:15:31Z"))).isEqualTo(EXAMPLE_RECORD);
    }

    @Test
    void theEntryHashMatchesTheIndependentlyComputedVectors() {
        assertThat(AuditChain.entryHash("retire.delete", 1, AuditChain.GENESIS_HASH, EXAMPLE_RECORD))
                .isEqualTo(EXAMPLE_HASH_1);
        assertThat(AuditChain.entryHash("retire.delete", 2, EXAMPLE_HASH_1, EXAMPLE_RECORD))
                .isEqualTo(EXAMPLE_HASH_2);
    }

    @Test
    void theSpecWorkedExampleIsTheTestedOne() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null && !java.nio.file.Files.isRegularFile(dir.resolve("docs/specs/audit-log.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("docs/specs/audit-log.md above the working directory").isNotNull();
        String spec = java.nio.file.Files.readString(dir.resolve("docs/specs/audit-log.md"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(spec).contains(EXAMPLE_RECORD).contains(EXAMPLE_HASH_1).contains(EXAMPLE_HASH_2)
                .contains(AuditChain.HASH_INPUT_PREFIX).contains(AuditChain.RECORD_SCHEMA).contains(AuditCheckpoint.FORMAT);
    }

    @Test
    void everyHashedFieldChangesTheHash() {
        String base = AuditChain.entryHash("retire.delete", 1, AuditChain.GENESIS_HASH, EXAMPLE_RECORD);
        assertThat(AuditChain.entryHash("retire.archive", 1, AuditChain.GENESIS_HASH, EXAMPLE_RECORD)).isNotEqualTo(base);
        assertThat(AuditChain.entryHash("retire.delete", 2, AuditChain.GENESIS_HASH, EXAMPLE_RECORD)).isNotEqualTo(base);
        assertThat(AuditChain.entryHash("retire.delete", 1, "1" + AuditChain.GENESIS_HASH.substring(1), EXAMPLE_RECORD))
                .isNotEqualTo(base);
        assertThat(AuditChain.entryHash("retire.delete", 1, AuditChain.GENESIS_HASH, EXAMPLE_RECORD.replace("1024", "1025")))
                .isNotEqualTo(base);
    }

    @Test
    void hashInputHasTheSpecLayout() {
        assertThat(AuditChain.hashInput("a.b", 7, "p", "{}")).isEqualTo("DLAP-AUDIT-1\na.b\n7\np\n{}");
    }

    @Test
    void optionalMembersAreLeftOutAndDetailsAreAlwaysPresent() {
        AuditEvent minimal = AuditEvent.builder("admin.role.granted")
                .occurredAt(Instant.parse("2026-01-02T03:04:05Z"))
                .actor(AuditActor.system("da-admin-service"))
                .outcome(AuditOutcome.REQUESTED)
                .build();
        assertThat(AuditChain.record(minimal, Instant.parse("2026-01-02T03:04:06Z"))).isEqualTo(
                "{\"actor\":{\"id\":\"da-admin-service\",\"kind\":\"SYSTEM\"},\"details\":{},"
                        + "\"eventType\":\"admin.role.granted\",\"occurredAt\":\"2026-01-02T03:04:05.000000Z\","
                        + "\"outcome\":\"REQUESTED\",\"recordedAt\":\"2026-01-02T03:04:06.000000Z\","
                        + "\"schema\":\"dlap-audit-record/1\"}");
    }

    @Test
    void canonicalJsonSortsEscapesAndStaysAscii() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", List.of(1L, true, "\u0001\u007f\ud83d\ude00\t"));
        m.put("a", null);
        m.put("m", Map.of("y", (short) 2, "b", (byte) 3, "n", BigInteger.valueOf(Long.MIN_VALUE)));
        String json = CanonicalJson.write(m);
        assertThat(json).isEqualTo("{\"a\":null,\"m\":{\"b\":3,\"n\":-9223372036854775808,\"y\":2},"
                + "\"z\":[1,true,\"\\u0001\\u007f\\ud83d\\ude00\\t\"]}");
        assertThat(CanonicalJson.isPrintableAscii(json)).isTrue();
        assertThat(CanonicalJson.write("\b\f\r\\/")).isEqualTo("\"\\b\\f\\r\\\\/\"");
    }

    @Test
    void canonicalJsonRefusesValuesWithoutOneForm() {
        assertThatThrownBy(() -> CanonicalJson.write(1.5d)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(Map.of(1, "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(BigInteger.TWO.pow(64))).isInstanceOf(IllegalArgumentException.class);
        List<Object> deep = new ArrayList<>();
        List<Object> cur = deep;
        for (int i = 0; i < CanonicalJson.MAX_DEPTH + 2; i++) {
            List<Object> next = new ArrayList<>();
            cur.add(next);
            cur = next;
        }
        assertThatThrownBy(() -> CanonicalJson.write(deep)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CanonicalJson.isPrintableAscii("")).isFalse();
        assertThat(CanonicalJson.isPrintableAscii(null)).isFalse();
        assertThat(CanonicalJson.isPrintableAscii("caf\u00e9")).isFalse();
    }

    @Test
    void eventValidationRejectsBadFields() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        AuditActor a = AuditActor.service("dlap-backend");
        assertThatThrownBy(() -> AuditEvent.builder("NoDots").occurredAt(t).actor(a).outcome(AuditOutcome.FAILED).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.builder("a.b").actor(a).outcome(AuditOutcome.FAILED).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AuditEvent.builder("a.b").occurredAt(t).actor(a).outcome(AuditOutcome.FAILED)
                .idempotencyKey("has space").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.builder("a.b").occurredAt(t).actor(a).outcome(AuditOutcome.FAILED)
                .detail("bad-key", "x").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.builder("a.b").occurredAt(t).actor(a).outcome(AuditOutcome.FAILED)
                .detail("ratio", 0.5d).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.builder("a.b").occurredAt(t).actor(a).outcome(AuditOutcome.FAILED)
                .detail("x", null).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.builder("a.b").occurredAt(t).actor(a).outcome(AuditOutcome.FAILED)
                .detail("x", "y".repeat(AuditEvent.MAX_DETAIL_STRING_LENGTH + 1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.builder("a.b").occurredAt(t).actor(a).outcome(AuditOutcome.FAILED)
                .detail("x", Map.of("y", Map.of("z", Map.of("w", Map.of("v", 1))))).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditActor.user("line\nbreak")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditActor.user("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditTarget.of("lower", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditStreams.requireValid("nodot")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditStreams.requireValid("a." + "b".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailsAreCopiedSoLaterChangesDoNotLeakIn() {
        Map<String, Object> details = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>(List.of("a"));
        details.put("items", list);
        AuditEvent e = new AuditEvent("a.b", Instant.EPOCH, AuditActor.system("x"), null, AuditOutcome.SUCCEEDED,
                null, null, details);
        list.add("b");
        details.put("later", 1);
        assertThat(e.details()).containsOnlyKeys("items");
        assertThat(e.details().get("items")).isEqualTo(List.of("a"));
    }

    @Test
    void assetTargetsAreNormalizedWhenTheyParse() {
        AuditTarget normalized = AuditTarget.asset("arn:aws:s3:::finance-reports");
        assertThat(normalized.kind()).isEqualTo(AuditTarget.ASSET);
        assertThat(normalized.id()).isEqualTo("s3://finance-reports");
        assertThat(AuditTarget.asset("not an id").id()).isEqualTo("not an id");
    }
}
