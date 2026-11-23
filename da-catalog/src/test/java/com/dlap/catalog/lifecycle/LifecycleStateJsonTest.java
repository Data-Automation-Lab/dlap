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

package com.dlap.catalog.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Rule R6 of docs/specs/lifecycle-state.md: one form. The renderer reproduces the published
 * examples member for member, keeps null members, renders instants as {@link Instant#toString()} does,
 * and the strict reader rejects anything that is not a 0.1 object.
 */
class LifecycleStateJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"valid-azure-container-unknown-read.json", "valid-bigquery-table-full-coverage-expired.json",
            "valid-s3-bucket-partial-coverage-legal-hold.json"})
    void everyPublishedExampleIsReproducedMemberForMember(String file) throws IOException {
        String published = Files.readString(LifecycleStateSchema.repoRoot().resolve(LifecycleStateSchema.EXAMPLES)
                .resolve(file), StandardCharsets.UTF_8);
        LifecycleState state = LifecycleStateJson.parse(LifecycleStateSchema.read(published));

        // The example files are hand-printed (short arrays inline), so the byte comparison is on the
        // compact form: same members, same order, same values. The pretty form is compared with the
        // OKF example of section 8.2 in OkfExporterTest.
        assertThat(LifecycleStateJson.compact(state)).isEqualTo(MAPPER.writeValueAsString(MAPPER.readTree(published)));
        assertThat(LifecycleStateSchema.read(LifecycleStateJson.pretty(state))).isEqualTo(MAPPER.readTree(published));
        LifecycleStateSchema.assertValid(LifecycleStateJson.compact(state));
    }

    @Test
    void membersAreInSpecificationOrderAndNullsArePresent() {
        LifecycleState unknown = new LifecycleState("0.1", "gs://b", new LifecycleState.Platform("GCP", "GCS_BUCKET"),
                Instant.parse("2026-10-31T12:00:00Z"), null, null, null, null, List.of(), null, null, null,
                new LifecycleState.Retention("NO_POLICY", null, null, null, null, null), false, null, List.of());
        JsonNode node = LifecycleStateJson.toNode(unknown);

        assertThat(names(node)).containsExactly("specVersion", "asset", "platform", "asOf", "createdAt", "lastReadAt",
                "lastWrittenAt", "readCoverage", "readCoverageReasons", "readCoverageSince", "sizeBytes",
                "sizeObservedAt", "retention", "legalHold", "disposition", "provenance");
        assertThat(names(node.get("retention"))).containsExactly("status", "basis", "days", "fixedDate", "expiresAt",
                "source");
        assertThat(node.get("lastReadAt").isNull()).as("null stays null, never omitted").isTrue();
        assertThat(node.get("sizeBytes").isNull()).isTrue();
        assertThat(LifecycleStateJson.compact(unknown)).contains("\"lastReadAt\":null").contains("\"disposition\":null");
        LifecycleStateSchema.assertValid(LifecycleStateJson.compact(unknown));
    }

    @Test
    void instantsUseTheShortestExactFractionAndSizesStayIntegers() {
        LifecycleState state = new LifecycleState("0.1", "gs://b", new LifecycleState.Platform("GCP", "GCS_BUCKET"),
                Instant.parse("2026-10-31T12:00:00.000Z"), Instant.parse("2026-01-01T00:00:00.120Z"),
                Instant.parse("2026-01-02T00:00:00.000123Z"), Instant.parse("2026-01-03T00:00:00.000000001Z"), null,
                List.of(), null, Long.MAX_VALUE, Instant.parse("2026-01-04T00:00:00Z"),
                new LifecycleState.Retention("NO_POLICY", null, null, null, null, null), false, null, List.of());
        JsonNode node = LifecycleStateJson.toNode(state);

        assertThat(node.get("asOf").asText()).isEqualTo("2026-10-31T12:00:00Z");
        assertThat(node.get("createdAt").asText()).isEqualTo("2026-01-01T00:00:00.120Z");
        assertThat(node.get("lastReadAt").asText()).isEqualTo("2026-01-02T00:00:00.000123Z");
        assertThat(node.get("lastWrittenAt").asText()).isEqualTo("2026-01-03T00:00:00.000000001Z");
        assertThat(node.get("sizeBytes").isIntegralNumber()).isTrue();
        assertThat(node.get("sizeBytes").asLong()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void prettyPrintsEmptyArraysAsTwoBrackets() {
        LifecycleState state = LifecycleStateJson.parse(LifecycleStateSchema.example(
                "valid-azure-container-unknown-read.json"));
        assertThat(LifecycleStateJson.pretty(state)).contains("\"bindingIds\": []").doesNotContain("[ ]")
                .doesNotContain("\r");
    }

    @Test
    void theStrictReaderRejectsAMissingMember() {
        ObjectNode node = azure();
        node.remove("sizeBytes");
        assertThatThrownBy(() -> LifecycleStateJson.parse(node)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes is missing");
    }

    @Test
    void theStrictReaderRejectsAnUnknownMember() {
        ObjectNode node = azure();
        node.put("owner", "alice");
        assertThatThrownBy(() -> LifecycleStateJson.parse(node)).hasMessageContaining("owner is not a member of 0.1");
    }

    @Test
    void theStrictReaderRejectsAnotherVersion() {
        ObjectNode node = azure();
        node.put("specVersion", "0.2");
        assertThatThrownBy(() -> LifecycleStateJson.parse(node)).hasMessageContaining("specVersion");
    }

    @Test
    void theStrictReaderRejectsATimestampOutsideTheOneForm() {
        ObjectNode node = azure();
        node.put("asOf", "2026-10-31T12:00:00.000Z");
        assertThatThrownBy(() -> LifecycleStateJson.parse(node)).hasMessageContaining("asOf is not in the one form");
        node.put("asOf", "2026-10-31T13:00:00+01:00");
        assertThatThrownBy(() -> LifecycleStateJson.parse(node)).hasMessageContaining("asOf");
    }

    @Test
    void theStrictReaderRejectsTheInvalidPublishedExamples() {
        assertThatThrownBy(() -> LifecycleStateJson.parse(LifecycleStateSchema.example(
                "invalid-last-read-at-number.json"))).hasMessageContaining("lastReadAt");
        assertThatThrownBy(() -> LifecycleStateJson.parse(LifecycleStateSchema.example(
                "invalid-disposition-without-evidence.json"))).hasMessageContaining("evidence");
    }

    @Test
    void theRecordRefusesADispositionWithoutEvidenceAndAnEntryWithoutIds() {
        assertThatThrownBy(() -> new LifecycleState.Disposition("delete", Instant.EPOCH, "engine", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LifecycleState.ProvenanceEntry("createdAt", null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode azure() {
        return (ObjectNode) LifecycleStateSchema.example("valid-azure-container-unknown-read.json");
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }
}
