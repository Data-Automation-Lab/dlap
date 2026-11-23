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

package com.dlap.logingest.parse.core.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LOG-02a acceptance: the JSON Schema of the mapping YAML validates the sample family file and
 * rejects malformed ones. It also pins the schema and the runtime validator together: every
 * structural defect is rejected by both, and the semantic-only defects are documents the schema
 * accepts and only {@link MappingSpecValidator} refuses.
 */
class MappingYamlSchemaTest {

    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = MappingYamlSchemaTest.class.getClassLoader()
                .getResourceAsStream(MappingSpecLoader.SCHEMA_RESOURCE)) {
            assertThat(in).as("schema on the classpath at %s", MappingSpecLoader.SCHEMA_RESOURCE).isNotNull();
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    private static Set<ValidationMessage> schemaErrors(JsonNode doc) {
        return schema.validate(doc);
    }

    @Test
    void theSchemaIsADraft202012Schema() {
        JsonNode raw = MappingSamples.json(MappingSamples.text(MappingSpecLoader.SCHEMA_RESOURCE));
        assertThat(raw.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(raw.get("properties").get("spec-version").get("const").asInt()).isEqualTo(MappingSpec.SPEC_VERSION);
    }

    @Test
    void theSampleFamilyFileValidates() {
        JsonNode sample = MappingSamples.tree(MappingSamples.SELFTEST);
        assertThat(schemaErrors(sample)).isEmpty();
        assertThat(MappingSpecValidator.validate(sample, MappingSamples.SELFTEST).problems()).isEmpty();
    }

    @Test
    void theRichSampleValidates() {
        JsonNode sample = MappingSamples.tree(MappingSamples.CONTRACT_DEMO);
        assertThat(schemaErrors(sample)).isEmpty();
        assertThat(MappingSpecValidator.validate(sample, MappingSamples.CONTRACT_DEMO).problems()).isEmpty();
    }

    static Stream<MappingSamples.Mutation> structural() {
        return MappingSamples.structural().stream();
    }

    static Stream<MappingSamples.Mutation> semanticOnly() {
        return MappingSamples.semanticOnly().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structural")
    void malformedFilesAreRejectedByTheSchemaAndTheValidator(MappingSamples.Mutation mutation) {
        JsonNode doc = mutation.apply(MappingSamples.tree(MappingSamples.SELFTEST));
        assertThat(schemaErrors(doc)).as("JSON Schema errors for: %s", mutation).isNotEmpty();
        assertThat(MappingSpecValidator.validate(doc, "mutated.yaml").problems())
                .as("validator problems for: %s", mutation).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("semanticOnly")
    void semanticDefectsPassTheSchemaAndFailTheValidator(MappingSamples.Mutation mutation) {
        JsonNode doc = mutation.apply(MappingSamples.tree(MappingSamples.SELFTEST));
        assertThat(schemaErrors(doc)).as("a JSON Schema cannot see: %s", mutation).isEmpty();
        assertThat(MappingSpecValidator.validate(doc, "mutated.yaml").problems())
                .as("validator problems for: %s", mutation).isNotEmpty();
    }
}
