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

package com.dlap.common.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lifecycle State 0.1 (docs/specs/lifecycle-state.md): every example under
 * {@code docs/specs/examples/lifecycle-state/} is checked against
 * {@code docs/specs/schemas/lifecycle-state-0.1.json}. Files named {@code valid-*.json} must
 * validate; files named {@code invalid-*.json} must fail, and fail on the field their name says.
 */
class LifecycleStateSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA = "docs/specs/schemas/lifecycle-state-0.1.json";
    private static final String FACET_SCHEMA = "docs/specs/schemas/openlineage-lifecycle-facet-0.1.json";
    private static final String EXAMPLES = "docs/specs/examples/lifecycle-state";

    /** The field each invalid example must be rejected for, keyed by file name. */
    private static final Map<String, String> INVALID_BECAUSE = Map.of(
            "invalid-last-read-at-number.json", "lastReadAt",
            "invalid-disposition-without-evidence.json", "evidence");

    private static Path repoRoot;
    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        repoRoot = findRepoRoot();
        try (var in = Files.newInputStream(repoRoot.resolve(SCHEMA))) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    /** Walks up from the working directory to the checkout that holds the spec files. */
    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve(SCHEMA))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot find " + SCHEMA + " above " + Path.of("").toAbsolutePath());
    }

    private static List<Path> examples(String prefix) {
        try (Stream<Path> files = Files.list(findRepoRoot().resolve(EXAMPLES))) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Stream<String> validExamples() {
        return examples("valid-").stream().map(p -> p.getFileName().toString());
    }

    static Stream<String> invalidExamples() {
        return examples("invalid-").stream().map(p -> p.getFileName().toString());
    }

    private static JsonNode example(String fileName) {
        return read(repoRoot.resolve(EXAMPLES).resolve(fileName));
    }

    /** A change to a valid example that one cross-field rule of the schema must reject. */
    record Mutation(String rule, String base, Consumer<ObjectNode> change) {
        @Override
        public String toString() {
            return rule;
        }
    }

    private static final String BQ = "valid-bigquery-table-full-coverage-expired.json";
    private static final String S3 = "valid-s3-bucket-partial-coverage-legal-hold.json";
    private static final String AZ = "valid-azure-container-unknown-read.json";

    static Stream<Mutation> ruleBreaks() {
        return Stream.of(
                new Mutation("a held asset is never deleted", S3,
                        n -> n.set("disposition", example(BQ).get("disposition").deepCopy())),
                new Mutation("a legal hold means retention status ON_HOLD", S3,
                        n -> ((ObjectNode) n.get("retention")).put("status", "RETAINED")),
                new Mutation("ON_HOLD needs a legal hold", S3, n -> n.put("legalHold", false)),
                new Mutation("a known lastReadAt needs provenance", BQ,
                        n -> removeProvenance(n, "lastReadAt")),
                new Mutation("an unknown lastReadAt has no provenance", BQ,
                        n -> n.putNull("lastReadAt")),
                new Mutation("a disposition needs provenance", BQ, n -> removeProvenance(n, "disposition")),
                new Mutation("a size needs its observation time", BQ, n -> n.putNull("sizeObservedAt")),
                new Mutation("readCoverageSince only with FULL", S3,
                        n -> n.put("readCoverageSince", "2026-03-02T06:41:00Z")),
                new Mutation("FULL needs readCoverageSince", BQ, n -> n.putNull("readCoverageSince")),
                new Mutation("FIXED_DATE carries no days", BQ,
                        n -> ((ObjectNode) n.get("retention")).put("days", 30)),
                new Mutation("a day rule carries no fixedDate", AZ,
                        n -> ((ObjectNode) n.get("retention")).put("fixedDate", "2027-01-01T00:00:00Z")),
                new Mutation("UNKNOWN has no expiry", AZ,
                        n -> ((ObjectNode) n.get("retention")).put("expiresAt", "2027-01-01T00:00:00Z")),
                new Mutation("DUE needs an expiry", BQ, n -> ((ObjectNode) n.get("retention")).putNull("expiresAt")),
                new Mutation("timestamps are UTC with Z", AZ, n -> n.put("asOf", "2026-10-31T12:00:00+01:00")),
                new Mutation("evidence ids are Log Event ids", BQ,
                        n -> ((ArrayNode) n.get("disposition").get("evidence")).set(0, "not-an-event-id")),
                new Mutation("unknown fields are rejected", AZ, n -> n.put("lastAccessedAt", "2026-01-01T00:00:00Z")),
                new Mutation("specVersion is 0.1", AZ, n -> n.put("specVersion", "0.2")));
    }

    private static void removeProvenance(ObjectNode n, String field) {
        ArrayNode kept = MAPPER.createArrayNode();
        n.get("provenance").forEach(e -> {
            if (!field.equals(e.get("field").asText())) {
                kept.add(e);
            }
        });
        n.set("provenance", kept);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleBreaks")
    void everyCrossFieldRuleRejectsItsViolation(Mutation mutation) {
        ObjectNode doc = (ObjectNode) example(mutation.base()).deepCopy();
        assertThat(schema.validate(doc)).as("base %s is valid", mutation.base()).isEmpty();
        mutation.change().accept(doc);
        assertThat(schema.validate(doc)).as("schema errors for: %s", mutation).isNotEmpty();
    }

    /** An archive executed before a hold stays on the record, so the record never drops history. */
    @Test
    void aHeldAssetKeepsAnArchiveFromBeforeTheHold() {
        ObjectNode doc = (ObjectNode) example(S3).deepCopy();
        ObjectNode archive = (ObjectNode) example(BQ).get("disposition").deepCopy();
        archive.put("action", "archive");
        doc.set("disposition", archive);
        for (JsonNode entry : example(BQ).get("provenance")) {
            if ("disposition".equals(entry.get("field").asText())) {
                doc.withArray("provenance").add(entry.deepCopy());
            }
        }
        assertThat(schema.validate(doc)).as("held asset with an earlier archive").isEmpty();
    }

    private static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theSchemaIsADraft202012SchemaForVersion01() {
        JsonNode raw = read(repoRoot.resolve(SCHEMA));
        assertThat(raw.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(raw.at("/properties/specVersion/const").asText()).isEqualTo("0.1");
    }

    @Test
    void theExampleSetHasThreeValidAndTwoInvalidFiles() {
        assertThat(examples("valid-")).hasSize(3);
        assertThat(examples("invalid-")).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrderElementsOf(INVALID_BECAUSE.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validExamples")
    void validExamplesPassTheSchema(String fileName) {
        Set<ValidationMessage> errors = schema.validate(example(fileName));
        assertThat(errors).as("schema errors for %s", fileName).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidExamples")
    void invalidExamplesFailTheSchemaOnTheNamedField(String fileName) {
        Set<ValidationMessage> errors = schema.validate(example(fileName));
        assertThat(errors).as("schema errors for %s", fileName).isNotEmpty();
        String field = INVALID_BECAUSE.get(fileName);
        assertThat(errors).as("an error names %s in %s", field, fileName)
                .anySatisfy(m -> assertThat(m.getMessage()).contains(field));
    }

    @Test
    void theOpenLineageFacetCarriesTheSameFieldsAsTheSchema() {
        JsonNode state = read(repoRoot.resolve(SCHEMA));
        JsonNode facet = read(repoRoot.resolve(FACET_SCHEMA));
        assertThat(facet.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        JsonNode def = facet.at("/$defs/LifecycleDatasetFacet/allOf");
        assertThat(def.get(0).get("$ref").asText())
                .isEqualTo("https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/DatasetFacet");
        assertThat(facet.at("/properties/lifecycle/$ref").asText()).isEqualTo("#/$defs/LifecycleDatasetFacet");
        assertThat(names(def.get(1).get("required"))).isEqualTo(names(state.get("required")));
        assertThat(fieldNames(def.get(1).get("properties"))).isEqualTo(fieldNames(state.get("properties")));
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
