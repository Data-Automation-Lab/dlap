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

package com.dlap.catalog.lifecycle.okf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.dlap.catalog.lifecycle.LifecycleStateSchema;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Test support: the frontmatter check of an OKF v0.1 concept file of Lifecycle State
 * (docs/specs/lifecycle-state.md section 8.2), written independently of {@link OkfExporter}.
 *
 * <p>The frontmatter is parsed with SnakeYAML (a real YAML parser, safe constructor), so quoting and
 * escaping are checked by a reader, not by string comparison. Checked: the path is
 * {@code lifecycle-state/<sha256 of asset>.md}; the frontmatter has exactly the keys {@code type},
 * {@code title}, {@code description}, {@code resource}, {@code tags} and {@code timestamp}, in that
 * order, with the values the mapping prescribes; the body holds the heading, the fixed sentence and
 * a {@code json} block that is a schema-valid object of the same asset and instant.
 */
public final class OkfFrontmatterCheck {

    /** The keys of the frontmatter, in order. */
    public static final List<String> KEYS = List.of("type", "title", "description", "resource", "tags", "timestamp");

    private OkfFrontmatterCheck() {
    }

    /**
     * Checks one concept file.
     *
     * @param path     the path inside the export
     * @param document the file text
     * @return the object of the {@code json} block
     */
    public static JsonNode check(String path, String document) {
        assertThat(document).as("frontmatter opens the file").startsWith("---\n");
        int end = document.indexOf("\n---\n", 4);
        assertThat(end).as("frontmatter is closed").isPositive();
        String yamlText = document.substring(4, end + 1);
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        assertThat(parsed).as("frontmatter is a YAML mapping").isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> front = (Map<String, Object>) parsed;
        assertThat(new ArrayList<>(front.keySet())).as("exactly these keys, in this order").isEqualTo(KEYS);

        String body = document.substring(end + 5);
        int open = body.indexOf("```json\n");
        int close = body.lastIndexOf("\n```\n");
        assertThat(open).as("a json block").isPositive();
        assertThat(close).isGreaterThan(open);
        String json = body.substring(open + "```json\n".length(), close);
        LifecycleStateSchema.assertValid(json);
        JsonNode state = LifecycleStateSchema.read(json);

        String asset = state.get("asset").asText();
        String provider = state.get("platform").get("provider").asText();
        String kind = state.get("platform").get("kind").asText();
        String asOf = state.get("asOf").asText();
        assertThat(path).isEqualTo("lifecycle-state/" + sha256(asset) + ".md");
        assertThat(front.get("type")).isEqualTo("lifecycle-state");
        assertThat(front.get("title")).isEqualTo(asset);
        assertThat(front.get("resource")).isEqualTo(asset);
        assertThat(front.get("description"))
                .isEqualTo("Lifecycle State of " + provider + " " + kind + " " + asset + " as of " + asOf + ".");
        assertThat(front.get("timestamp")).as("timestamp is the double-quoted asOf, a string, not a YAML date")
                .isEqualTo(asOf);
        assertThat(front.get("tags")).isEqualTo(expectedTags(state));
        assertThat(yamlText).contains("title: \"").contains("resource: \"").contains("timestamp: \"")
                .contains("description: \"");

        assertThat(body).startsWith("\n# " + asset + "\n\n" + OkfExporter.SENTENCE + "\n\n| Field | Value |\n|---|---|\n");
        assertThat(document).endsWith("\n```\n");
        return state;
    }

    /** The tags of section 8.2, derived from the object. */
    static List<String> expectedTags(JsonNode state) {
        List<String> tags = new ArrayList<>();
        tags.add("lifecycle-state");
        tags.add(state.get("platform").get("provider").asText().toLowerCase(Locale.ROOT));
        tags.add(state.get("platform").get("kind").asText().toLowerCase(Locale.ROOT).replace('_', '-'));
        JsonNode coverage = state.get("readCoverage");
        tags.add("read-coverage-" + (coverage.isNull() ? "unknown" : coverage.asText().toLowerCase(Locale.ROOT)));
        tags.add("retention-" + state.get("retention").get("status").asText().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (state.get("legalHold").asBoolean()) {
            tags.add("legal-hold");
        }
        JsonNode disposition = state.get("disposition");
        tags.add("disposition-" + (disposition.isNull() ? "unknown" : disposition.get("action").asText()));
        return tags;
    }

    /**
     * The files of an export.
     *
     * @param zip the archive
     * @return path to text, in archive order
     * @throws java.io.IOException when the archive cannot be read
     */
    public static Map<String, String> unzip(byte[] zip) throws java.io.IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
