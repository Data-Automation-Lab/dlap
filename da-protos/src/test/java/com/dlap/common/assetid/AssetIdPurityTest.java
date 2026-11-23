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

package com.dlap.common.assetid;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards for the package itself: pure Java (no provider SDK or framework imports, CLAUDE.md
 * section 5), the spec names every kind, and no em-dash or en-dash in the sources or the spec.
 * Gradle runs tests with the module directory as the working directory.
 */
class AssetIdPurityTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "dlap", "common", "assetid");
    private static final Path SPEC = Path.of("..", "docs", "specs", "asset-id.md");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.list(SOURCES)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void importsOnlyJavaAndThePackageItself() throws IOException {
        List<String> offending = new ArrayList<>();
        List<Path> files = sources();
        assertThat(files).hasSizeGreaterThanOrEqualTo(10);
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("import ") && !t.startsWith("import java.") && !t.startsWith("import static java.")
                        && !t.startsWith("import com.dlap.common.assetid.")) {
                    offending.add(file.getFileName() + ": " + t);
                }
            }
        }
        assertThat(offending).isEmpty();
    }

    @Test
    void specNamesEveryKindAndProvider() throws IOException {
        String spec = Files.readString(SPEC, StandardCharsets.UTF_8);
        for (AssetKind k : AssetKind.values()) {
            assertThat(spec).as("spec mentions %s", k).contains("`" + k.name() + "`");
        }
        for (AssetProvider p : AssetProvider.values()) {
            assertThat(spec).as("spec mentions %s", p).contains("`" + p.name() + "`");
        }
        assertThat(spec).contains("Spec version: " + AssetIdNormalizer.SPEC_VERSION);
    }

    @Test
    void noEmDashOrEnDash() throws IOException {
        List<Path> files = new ArrayList<>(sources());
        files.add(SPEC);
        try (Stream<Path> tests = Files.list(Path.of("src", "test", "java", "com", "dlap", "common", "assetid"))) {
            tests.forEach(files::add);
        }
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(text.indexOf('\u2014')).as("em-dash in %s", file).isEqualTo(-1);
            assertThat(text.indexOf('\u2013')).as("en-dash in %s", file).isEqualTo(-1);
        }
    }
}
