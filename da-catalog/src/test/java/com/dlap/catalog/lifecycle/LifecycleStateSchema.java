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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Test support: the published Lifecycle State 0.1 schema ({@code docs/specs/schemas/lifecycle-state-0.1.json})
 * loaded with the networknt validator, the one da-protos' {@code LifecycleStateSchemaTest} uses, and
 * the spec text itself for exact-form comparisons.
 */
public final class LifecycleStateSchema {

    public static final String SCHEMA = "docs/specs/schemas/lifecycle-state-0.1.json";
    public static final String SPEC = "docs/specs/lifecycle-state.md";
    public static final String EXAMPLES = "docs/specs/examples/lifecycle-state";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema schema;

    private LifecycleStateSchema() {
    }

    /** Walks up from the working directory to the checkout that holds the spec files. */
    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve(SCHEMA))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot find " + SCHEMA + " above " + Path.of("").toAbsolutePath());
    }

    public static synchronized JsonSchema schema() {
        if (schema == null) {
            try (InputStream in = Files.newInputStream(repoRoot().resolve(SCHEMA))) {
                schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return schema;
    }

    /** The validation messages of a JSON text; empty when it is valid. */
    public static Set<ValidationMessage> validate(String json) {
        return schema().validate(read(json));
    }

    /** Asserts that a JSON text is a valid Lifecycle State 0.1 object. */
    public static void assertValid(String json) {
        assertThat(validate(json)).as("schema violations of %s", json).isEmpty();
    }

    public static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode example(String fileName) {
        try {
            return MAPPER.readTree(repoRoot().resolve(EXAMPLES).resolve(fileName).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String spec() {
        try {
            return Files.readString(repoRoot().resolve(SPEC), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
