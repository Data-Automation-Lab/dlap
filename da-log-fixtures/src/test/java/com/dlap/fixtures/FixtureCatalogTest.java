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

package com.dlap.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.fixtures.FixtureCase.Origin;
import com.dlap.fixtures.TestCorpus.Meta;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureCatalogTest {

    @TempDir
    Path tmp;

    @Test
    void classpathCorpusLoadsTheSelftestFamily() {
        FixtureCatalog catalog = FixtureCatalog.fromClasspath();
        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.familyNames()).contains("selftest");
        assertThat(catalog.family("selftest")).get().extracting(FixtureCatalog.Family::selftest).isEqualTo(true);
        assertThat(catalog.cases("selftest")).extracting(FixtureCase::id).containsExactly(
                "selftest/generic-json/1/delete-ok",
                "selftest/generic-json/1/read-ok",
                "selftest/generic-json/1/write-denied",
                "selftest/generic-json/2/read-ok",
                "selftest/negative/generic-json/1/unknown-major");
        assertThat(catalog.cases("selftest")).allMatch(FixtureCase::selftest, "selftest family flag");
    }

    @Test
    void caseAccessorsReadTheFiles() {
        FixtureCatalog catalog = FixtureCatalog.fromClasspath();
        FixtureCase read = catalog.cases().stream()
                .filter(c -> c.id().equals("selftest/generic-json/1/read-ok")).findFirst().orElseThrow();
        assertThat(read.negative()).isFalse();
        assertThat(read.family()).isEqualTo("selftest");
        assertThat(read.envelope()).isEqualTo("generic-json");
        assertThat(read.version()).isEqualTo("1");
        assertThat(read.name()).isEqualTo("read-ok");
        assertThat(read.meta().origin()).isEqualTo(Origin.SYNTHETIC_FROM_DOC);
        assertThat(read.meta().covers()).containsExactly("demo:read");
        assertThat(read.meta().docDate()).hasToString("2017-12-01");
        assertThat(read.inputFiles()).hasSize(1);
        assertThat(read.inputText()).contains("\"op\":\"read\"");
        assertThat(read.expectedTree().get("outcome").asText()).isEqualTo("PARSED");

        FixtureCase negative = catalog.cases("selftest").stream().filter(FixtureCase::negative).findFirst().orElseThrow();
        assertThat(negative.id()).isEqualTo("selftest/negative/generic-json/1/unknown-major");
        assertThat(negative.meta().expectedOutcome()).isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
    }

    @Test
    void gzipInputIsDecompressedByInputTextAndKeptRawByInputBytes() throws IOException {
        TestCorpus c = TestCorpus.valid(tmp);
        Files.delete(tmp.resolve("fam/env/1/read/input.json"));
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write("{\"Records\":[]}".getBytes(StandardCharsets.UTF_8));
        }
        Files.write(tmp.resolve("fam/env/1/read/input.json.gz"), gz.toByteArray());

        FixtureCase read = FixtureCatalog.load(c.root()).cases().stream()
                .filter(x -> x.name().equals("read")).findFirst().orElseThrow();
        assertThat(read.inputText()).isEqualTo("{\"Records\":[]}");
        assertThat(read.inputBytes()).isEqualTo(gz.toByteArray());
    }

    @Test
    void corruptGzipIsReportedByInputText() throws IOException {
        TestCorpus c = TestCorpus.valid(tmp);
        Files.delete(tmp.resolve("fam/env/1/read/input.json"));
        Files.write(tmp.resolve("fam/env/1/read/input.json.gz"), new byte[] {1, 2, 3, 4});
        FixtureCase read = FixtureCatalog.load(c.root()).cases().stream()
                .filter(x -> x.name().equals("read")).findFirst().orElseThrow();
        assertThatThrownBy(read::inputText).hasMessageContaining("not valid gzip");
    }

    @Test
    void loadFailsWithEveryProblemListed() {
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/env/1/read/meta.yaml").delete("fam/NOTICE.md");
        c.write("fam/env/1/write/meta.yaml", "origin: nope\n");
        assertThatThrownBy(() -> FixtureCatalog.load(c.root()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fam/env/1/read: case has no meta.yaml")
                .hasMessageContaining("origin 'nope' is not one of");
    }

    @Test
    void scanKeepsReadableCasesWhenOthersAreBroken() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/env/1/write/meta.yaml", "origin: nope\n");
        FixtureCatalog catalog = FixtureCatalog.scan(c.root());
        assertThat(catalog.problems()).isNotEmpty();
        assertThat(catalog.cases()).extracting(FixtureCase::name).containsExactlyInAnyOrder("read", "bad-gzip");
    }

    @Test
    void nestedCaseDirectoriesAndBadSegmentsAreProblems() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/read/inner", Meta.synthetic())
                .caseAt("fam/env/1/bad name", Meta.synthetic());
        assertThat(FixtureCatalog.scan(c.root()).problems())
                .anyMatch(p -> p.contains("nested inside another case directory"))
                .anyMatch(p -> p.contains("every path segment must match"));
    }

    @Test
    void hiddenDirectoriesAreIgnored() {
        TestCorpus c = TestCorpus.valid(tmp).write(".git/keep", "x").write("fam/env/1/.hidden/input.json", "{}");
        assertThat(FixtureCatalog.scan(c.root()).problems()).isEmpty();
        assertThat(FixtureCatalog.scan(c.root()).familyNames()).containsExactly("fam");
    }

    @Test
    void versionIdsKeepTheirTrailingZeroWhenQuoted() {
        String catalog = """
                family: fam
                envelopes:
                  - id: env
                    versions:
                      - id: "1.10"
                        operations: [svc:Read]
                        variants: []
                """;
        TestCorpus c = TestCorpus.valid(tmp).write("fam/catalog.yaml", catalog);
        FixtureCatalog loaded = FixtureCatalog.scan(c.root());
        assertThat(loaded.family("fam")).get().satisfies(f ->
                assertThat(f.version("env", "1.10")).isPresent());
        assertThat(loaded.family("fam").orElseThrow().version("env", "1.1")).isEmpty();
    }

    @Test
    void corpusLoadsFromInsideAJarWithoutDirectoryEntries() throws Exception {
        TestCorpus c = TestCorpus.valid(tmp.resolve("src"));
        c.write("README.md", "marker\n");
        Path jar = tmp.resolve("fixtures.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            try (Stream<Path> files = Files.walk(c.root())) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String name = FixtureCatalog.RESOURCE_ROOT + "/" + c.root().relativize(f).toString().replace('\\', '/');
                    out.putNextEntry(new JarEntry(name));
                    out.write(Files.readAllBytes(f));
                    out.closeEntry();
                }
            }
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            FixtureCatalog catalog = FixtureCatalog.fromClasspath(loader);
            assertThat(catalog.cases()).extracting(FixtureCase::id).containsExactly(
                    "fam/env/1/read", "fam/env/1/write", "fam/negative/env/1/bad-gzip");
            assertThat(catalog.cases().get(0).inputText()).contains("\"k\":1");
            // a second load of the same jar reuses the open file system
            assertThat(FixtureCatalog.fromClasspath(loader).cases()).hasSize(3);
        }
    }

    @Test
    void missingOrDuplicateCorpusOnTheClasspathFailsClearly() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertThatThrownBy(() -> FixtureCatalog.fromClasspath(empty))
                    .hasMessageContaining("no dlap-fixtures/README.md on the classpath");
        }
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Files.createDirectories(a.resolve("dlap-fixtures"));
        Files.createDirectories(b.resolve("dlap-fixtures"));
        Files.writeString(a.resolve("dlap-fixtures/README.md"), "a");
        Files.writeString(b.resolve("dlap-fixtures/README.md"), "b");
        try (URLClassLoader both = new URLClassLoader(new URL[] {a.toUri().toURL(), b.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> FixtureCatalog.fromClasspath(both))
                    .hasMessageContaining("more than one fixture corpus");
        }
    }

    // ------------------------------------------------------------------
    // Coverage specs and evidence (SPEC-01)
    // ------------------------------------------------------------------

    @Test
    void aFamilyWithOnlyASpecNeedsNoCatalogYet() {
        TestCorpus c = new TestCorpus(tmp).write("fam/coverage-spec.yaml", TestCorpus.VALID_SPEC);
        FixtureCatalog catalog = FixtureCatalog.scan(c.root());
        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.family("fam")).isEmpty();
        assertThat(catalog.spec("fam")).isPresent();
        assertThat(catalog.familyNames()).containsExactly("fam");
    }

    @Test
    void theSelftestFlagIsReservedForTheToolingFamily() {
        String flagged = TestCorpus.VALID_CATALOG.replace("family: fam\n", "family: fam\nselftest: true\n");
        TestCorpus c = TestCorpus.valid(tmp).write("fam/catalog.yaml", flagged);
        FixtureCatalog catalog = FixtureCatalog.scan(c.root());
        assertThat(catalog.problems()).anyMatch(p -> p.equals("fam/catalog.yaml: 'selftest: true' is reserved for "
                + "the tooling family 'selftest'; a log family cannot exempt itself from its independent "
                + "coverage-spec.yaml"));
        assertThat(catalog.family("fam")).isEmpty();
        assertThatThrownBy(() -> FixtureCatalog.load(c.root()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'selftest: true' is reserved for the tooling family 'selftest'");

        // The tooling family has to declare itself, so its cases stay out of real parser walks.
        String unflagged = TestCorpus.VALID_CATALOG.replace("family: fam\n", "family: selftest\n");
        TestCorpus t = new TestCorpus(tmp.resolve("t")).write("selftest/catalog.yaml", unflagged);
        assertThat(FixtureCatalog.scan(t.root()).problems())
                .containsExactly("selftest/catalog.yaml: the tooling family 'selftest' must say 'selftest: true'");

        String tooling = TestCorpus.VALID_CATALOG.replace("family: fam\n", "family: selftest\nselftest: true\n");
        TestCorpus ok = new TestCorpus(tmp.resolve("ok")).write("selftest/catalog.yaml", tooling);
        FixtureCatalog okCatalog = FixtureCatalog.scan(ok.root());
        assertThat(okCatalog.problems()).isEmpty();
        assertThat(okCatalog.family(FixtureCatalog.SELFTEST_FAMILY)).get()
                .extracting(FixtureCatalog.Family::selftest).isEqualTo(true);
        assertThat(FixtureCatalog.isToolingFamily("selftest")).isTrue();
        assertThat(FixtureCatalog.isToolingFamily("fam")).isFalse();
        assertThat(FixtureCatalog.isToolingFamily("Selftest")).isFalse();
    }

    @Test
    void casesStillNeedTheParserPackagesCatalog() {
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/catalog.yaml");
        assertThat(FixtureCatalog.scan(c.root()).problems())
                .anyMatch(p -> p.startsWith("fam/catalog.yaml: missing; every family with cases needs a catalog.yaml"));

        TestCorpus noSpec = new TestCorpus(tmp.resolve("bare")).write("fam/README.txt", "nothing here\n");
        assertThat(FixtureCatalog.scan(noSpec.root()).problems())
                .anyMatch(p -> p.startsWith("fam/catalog.yaml: missing"));
    }

    @Test
    void confirmationsAndGeneratorAreReadAndClassified() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/rt", Meta.synthetic()
                        .confirmedBy("proto-round-trip", "com.dlap.logingest.parse.fam.RoundTripTest")
                        .confirmedBy("otel-expected", "open-telemetry/opentelemetry-collector-contrib@"
                                + "0123456789abcdef0123456789abcdef01234567:testdata/x_expected.yaml"))
                .caseAt("fam/env/1/gen", Meta.synthetic().generator("com.dlap.fixtures.gen.fam.FamGenerator"))
                .caseAt("fam/env/1/emu", Meta.synthetic().emulator())
                .caseAt("fam/env/1/own", Meta.synthetic().ownAccount());
        FixtureCatalog catalog = FixtureCatalog.load(c.root());
        java.util.Map<String, FixtureCase> byName = new java.util.HashMap<>();
        catalog.cases().forEach(x -> byName.put(x.name(), x));

        FixtureCase rt = byName.get("rt");
        assertThat(rt.meta().confirmedBy()).hasSize(2);
        assertThat(rt.meta().confirmedBy().get(0).method()).isEqualTo(FixtureCase.ConfirmationMethod.PROTO_ROUND_TRIP);
        assertThat(rt.meta().confirmedBy().get(0).evidence()).isEqualTo("com.dlap.logingest.parse.fam.RoundTripTest");
        assertThat(rt.evidence()).isEqualTo(FixtureCase.Evidence.CONFIRMED);
        assertThat(byName.get("gen").meta().generator()).isEqualTo("com.dlap.fixtures.gen.fam.FamGenerator");
        assertThat(byName.get("gen").evidence()).isEqualTo(FixtureCase.Evidence.GENERATED);
        assertThat(byName.get("emu").evidence()).isEqualTo(FixtureCase.Evidence.EMULATOR);
        assertThat(byName.get("own").evidence()).isEqualTo(FixtureCase.Evidence.CONFIRMED);
        assertThat(byName.get("own").confirmationMethods()).containsExactly(FixtureCase.ConfirmationMethod.OWN_ACCOUNT_CAPTURE);
        assertThat(byName.get("read").evidence()).isEqualTo(FixtureCase.Evidence.AUTHORED);
        assertThat(byName.get("read").meta().confirmedBy()).isEmpty();
        assertThat(byName.get("read").meta().generator()).isNull();
    }

    @Test
    void malformedConfirmationsAreProblems() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/a", Meta.synthetic().confirmedBy("vibes", "x"))
                .caseAt("fam/env/1/b", Meta.synthetic().set("confirmed_by", "proto-round-trip"))
                .caseAt("fam/env/1/c", Meta.synthetic().set("confirmed_by", "[{method: proto-round-trip}]"))
                .caseAt("fam/env/1/d", Meta.synthetic().set("confirmed_by", "[{method: proto-round-trip, evidence: x, by: me}]"));
        assertThat(FixtureCatalog.scan(c.root()).problems())
                .anyMatch(p -> p.contains("fam/env/1/a") && p.contains("confirmed_by method 'vibes' is not one of"))
                .anyMatch(p -> p.contains("fam/env/1/b") && p.contains("'confirmed_by' must be a list"))
                .anyMatch(p -> p.contains("fam/env/1/c") && p.contains("missing required key 'evidence'"))
                .anyMatch(p -> p.contains("fam/env/1/d") && p.contains("unknown key 'by'"));
    }
}
