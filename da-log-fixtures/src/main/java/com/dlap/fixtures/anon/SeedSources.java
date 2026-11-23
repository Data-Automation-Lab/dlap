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

package com.dlap.fixtures.anon;

import com.dlap.fixtures.anon.SeedManifest.Case;
import com.dlap.fixtures.anon.SeedManifest.Source;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the raw records of a case from a local checkout of a public repository. The checkout is only
 * data: nothing in it is executed, and nothing outside the named files is read.
 *
 * <p>The readers know the layouts of the five sources the seed import uses:
 * <ul>
 *   <li>{@code cloudtrail-files}: trail files, {@code {"Records": [...]}} (invictus-ir/aws_dataset);</li>
 *   <li>{@code panther-rule}: a rule YAML whose {@code Tests[].Log} are sample records
 *       (panther-labs/panther-analysis);</li>
 *   <li>{@code gcp-example}: a log file with a description and one or more pretty printed LogEntry
 *       objects whose braces start in column one (GoogleCloudPlatform/professional-services);</li>
 *   <li>{@code json-document}: one JSON document (googleapis/google-cloudevents test data);</li>
 *   <li>{@code otel-records}: a diagnostic export {@code {"records": [...]}}
 *       (open-telemetry/opentelemetry-collector-contrib).</li>
 * </ul>
 */
final class SeedSources {

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private SeedSources() {
    }

    /**
     * Reads the records a case selects.
     *
     * @param source   the source the case names
     * @param checkout the local checkout of that source's repository
     * @param c        the case
     * @return the raw records in selection order
     * @throws IllegalArgumentException when the file or a selected record is missing
     */
    static List<JsonNode> read(Source source, Path checkout, Case c) {
        Path base = source.root() == null ? checkout : checkout.resolve(source.root());
        Path file = resolve(base, c.file());
        List<JsonNode> candidates = switch (source.kind()) {
            case "cloudtrail-files" -> field(parseJson(file), "Records", file);
            case "otel-records" -> field(parseJson(file), "records", file);
            case "json-document" -> List.of(parseJson(file));
            case "gcp-example" -> gcpEntries(file);
            case "panther-rule" -> pantherLogs(file);
            default -> throw new IllegalArgumentException("unknown source kind " + source.kind());
        };
        if (!c.eventIds().isEmpty()) {
            List<JsonNode> out = new ArrayList<>();
            for (String id : c.eventIds()) {
                JsonNode hit = candidates.stream().filter(r -> id.equals(r.path("eventID").asText())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(c.name() + ": no record with eventID " + id
                                + " in " + c.file()));
                out.add(hit);
            }
            return out;
        }
        if (!c.indices().isEmpty()) {
            List<JsonNode> out = new ArrayList<>();
            for (int i : c.indices()) {
                if (i >= candidates.size() || candidates.get(i) == null || !candidates.get(i).isObject()) {
                    throw new IllegalArgumentException(c.name() + ": " + c.file() + " has no object record at index " + i
                            + " (it has " + candidates.size() + " candidates)");
                }
                out.add(candidates.get(i));
            }
            return out;
        }
        List<JsonNode> all = new ArrayList<>();
        candidates.stream().filter(n -> n != null && n.isObject()).forEach(all::add);
        if (all.isEmpty()) {
            throw new IllegalArgumentException(c.name() + ": " + c.file() + " holds no records");
        }
        return all;
    }

    private static Path resolve(Path base, String file) {
        int star = file.indexOf('*');
        if (star < 0) {
            Path p = base.resolve(file).normalize();
            if (!p.startsWith(base.normalize()) || !Files.isRegularFile(p)) {
                throw new IllegalArgumentException("no such file under the source root: " + file);
            }
            return p;
        }
        int slash = file.lastIndexOf('/', star);
        Path dir = slash < 0 ? base : base.resolve(file.substring(0, slash));
        String pattern = file.substring(slash + 1);
        Pattern re = Pattern.compile(Pattern.quote(pattern.substring(0, pattern.indexOf('*'))) + ".*"
                + Pattern.quote(pattern.substring(pattern.indexOf('*') + 1)));
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> hits = files.filter(p -> re.matcher(p.getFileName().toString()).matches()).sorted().toList();
            if (hits.size() != 1) {
                throw new IllegalArgumentException(file + " matches " + hits.size() + " files, expected exactly one");
            }
            return hits.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static JsonNode parseJson(Path file) {
        try {
            return SeedJson.parse(readText(file));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static List<JsonNode> field(JsonNode doc, String key, Path file) {
        JsonNode arr = doc.get(key);
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException(file.getFileName() + " has no '" + key + "' array");
        }
        List<JsonNode> out = new ArrayList<>();
        arr.forEach(out::add);
        return out;
    }

    private static List<JsonNode> gcpEntries(Path file) {
        List<JsonNode> out = new ArrayList<>();
        StringBuilder block = null;
        for (String line : readText(file).split("\r?\n", -1)) {
            if (line.equals("{")) {
                block = new StringBuilder("{\n");
            } else if (line.equals("}") && block != null) {
                block.append("}");
                out.add(SeedJson.parse(block.toString()));
                block = null;
            } else if (block != null) {
                block.append(line).append('\n');
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(file.getFileName() + " holds no LogEntry block");
        }
        return out;
    }

    private static List<JsonNode> pantherLogs(Path file) {
        JsonNode doc;
        try {
            doc = YAML.readTree(readText(file));
        } catch (IOException e) {
            throw new IllegalArgumentException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
        }
        JsonNode tests = doc == null ? null : doc.get("Tests");
        if (tests == null || !tests.isArray()) {
            throw new IllegalArgumentException(file.getFileName() + " has no Tests list");
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode t : tests) {
            out.add(t.get("Log"));
        }
        return out;
    }
}
