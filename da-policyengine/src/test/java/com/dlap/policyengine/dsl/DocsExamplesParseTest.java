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

package com.dlap.policyengine.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Drift guard: every YAML document under {@code docs/examples/} must parse with
 * {@link PolicyDslParser}. The published examples are the first thing an adopter
 * copies, so they are held to the same contract as the parser (Q-025: the
 * compliance examples once declared {@code kind: LifecyclePolicy}, which the
 * parser rejects).
 *
 * <p>Multi-document files ({@code ---} separators) are split and each document
 * is parsed on its own, the same way each one is published as a separate
 * policy. The test fails, rather than skips, when the examples directory cannot
 * be found, so the guard can never pass vacuously. Since POL-02 each document's
 * match block must also be free of clause defects ({@link PolicyDslEvaluator#defects}).
 */
class DocsExamplesParseTest {

    /** A YAML document separator line. */
    private static final Pattern DOC_SEPARATOR = Pattern.compile("(?m)^---[ \\t]*$");

    private final PolicyDslParser parser = new PolicyDslParser();

    @TestFactory
    Stream<DynamicTest> everyExampleDocumentParses() throws IOException {
        Path examplesDir = locateExamplesDir();
        List<Path> yamlFiles;
        try (Stream<Path> walk = Files.walk(examplesDir)) {
            yamlFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        }
        assertThat(yamlFiles)
                .as("expected YAML policy examples under %s", examplesDir)
                .isNotEmpty();

        List<DynamicTest> tests = new ArrayList<>();
        for (Path file : yamlFiles) {
            String relative = examplesDir.relativize(file).toString().replace('\\', '/');
            List<String> documents = splitDocuments(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(documents)
                    .as("%s contains no YAML document", relative)
                    .isNotEmpty();
            for (int i = 0; i < documents.size(); i++) {
                String doc = documents.get(i);
                String name = relative + " [document " + (i + 1) + "]";
                tests.add(DynamicTest.dynamicTest(name, () -> assertParses(name, doc)));
            }
        }
        return tests.stream();
    }

    /** Pins the Q-025 decision: {@code LifecyclePolicy} is not an accepted kind. */
    @Test
    void lifecyclePolicyKindIsNotAnAlias() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: LifecyclePolicy\n"
                + "metadata:\n"
                + "  id: not-an-alias\n"
                + "spec:\n"
                + "  triggers:\n"
                + "    - type: event\n"
                + "      topic: dlap.catalog.asset.upserted\n"
                + "  action:\n"
                + "    type: alert\n";

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("unsupported kind: LifecyclePolicy");
    }

    private void assertParses(String name, String doc) {
        PolicyDsl dsl;
        try {
            dsl = parser.parse(doc);
        } catch (PolicyDslParser.PolicyDslParseException e) {
            fail("docs/examples/" + name + " does not parse: " + e.getMessage(), e);
            return;
        }
        assertThat(dsl.getKind())
                .as("docs/examples/%s kind", name)
                .isIn(PolicyDsl.KIND_POLICY, PolicyDsl.KIND_ACCESS_POLICY);
        // POL-02: no published example carries a malformed match clause.
        assertThat(PolicyDslEvaluator.defects(dsl))
                .as("docs/examples/%s match-clause defects", name)
                .isEmpty();
    }

    /**
     * Split a YAML stream into its documents, dropping chunks that hold only
     * comments or whitespace (for example a header before the first separator).
     */
    static List<String> splitDocuments(String text) {
        List<String> documents = new ArrayList<>();
        for (String chunk : DOC_SEPARATOR.split(text)) {
            boolean hasContent = chunk.lines()
                    .map(String::strip)
                    .anyMatch(line -> !line.isEmpty() && !line.startsWith("#"));
            if (hasContent) {
                documents.add(chunk);
            }
        }
        return documents;
    }

    /**
     * Find {@code docs/examples} by walking up from the working directory, so the
     * test works whether it runs from the module directory (Gradle) or the
     * repository root (IDE).
     */
    private static Path locateExamplesDir() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve("examples");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return fail("docs/examples not found in " + start + " or any parent directory");
    }
}
