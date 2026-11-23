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
import static org.assertj.core.api.Assertions.within;

import com.dlap.fixtures.CoverageReport.Denominator;
import com.dlap.fixtures.CoverageReport.Row;
import com.dlap.fixtures.FixtureCase.Evidence;
import com.dlap.fixtures.TestCorpus.Meta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageReportTest {

    private static final String TEST_EVIDENCE = "com.dlap.logingest.parse.fam.FamProtoRoundTripTest";

    @TempDir
    Path tmp;

    private CoverageReport report(TestCorpus corpus) {
        FixtureCatalog catalog = FixtureCatalog.scan(corpus.root());
        assertThat(catalog.problems()).as("test corpus must be well formed").isEmpty();
        return CoverageReport.compute(catalog);
    }

    private Row onlyRow(CoverageReport report) {
        assertThat(report.rows()).hasSize(1);
        return report.rows().get(0);
    }

    private static TestCorpus withoutWriteCase(TestCorpus c) {
        return withoutWriteCaseOf(c, "fam");
    }

    private static TestCorpus withoutWriteCaseOf(TestCorpus c, String family) {
        String dir = family + "/env/1/write/";
        return c.delete(dir + "meta.yaml").delete(dir + "input.json").delete(dir + "expected.json");
    }

    // ------------------------------------------------------------------
    // Score arithmetic (FIX-01 behaviour, now over the spec denominator)
    // ------------------------------------------------------------------

    @Test
    void fullyCoveredVersionScoresOne() {
        Row row = onlyRow(report(TestCorpus.valid(tmp)));
        assertThat(row.family()).isEqualTo("fam");
        assertThat(row.denominator()).isEqualTo(Denominator.SPEC);
        assertThat(row.claimed()).isTrue();
        assertThat(row.supported()).isTrue();
        assertThat(row.operationsCovered()).isEqualTo(2);
        assertThat(row.operationsDocumented()).isEqualTo(2);
        assertThat(row.variantsCovered()).isEqualTo(2);
        assertThat(row.variantsDocumented()).isEqualTo(2);
        assertThat(row.score()).isEqualTo(1.0);
        assertThat(row.driftKillRatio()).isNull();
        assertThat(row.caseCount(Evidence.AUTHORED)).isEqualTo(3);
        assertThat(report(TestCorpus.valid(tmp.resolve("again"))).gateFailures(0.99)).isEmpty();
    }

    @Test
    void scoreIsTheProductOfOperationAndVariantRatios() {
        // Drop the write case: 1 of 2 operations, and the denied variant is lost with it: 1 of 2 variants.
        Row row = onlyRow(report(withoutWriteCase(TestCorpus.valid(tmp))));
        assertThat(row.operationsRatio()).isCloseTo(0.5, within(1e-12));
        assertThat(row.variantsRatio()).isCloseTo(0.5, within(1e-12));
        assertThat(row.score()).isCloseTo(0.25, within(1e-12));
        assertThat(row.missingOperations()).containsExactly("svc:Write");
        assertThat(row.missingVariants()).containsExactly("v-denied");
    }

    @Test
    void negativeCasesCoverVariantsButNotOperations() {
        // A negative case that claims the Write operation must not count as an operation case.
        TestCorpus c = withoutWriteCase(TestCorpus.valid(tmp));
        c.caseAt("fam/negative/env/1/bad-gzip", Meta.synthetic().covers("svc:Write").variants("v-bad-gzip", "v-denied"));
        Row row = onlyRow(report(c));
        assertThat(row.missingOperations()).containsExactly("svc:Write");
        assertThat(row.missingVariants()).isEmpty();
        assertThat(row.undeclaredOperations()).isEmpty();
    }

    @Test
    void casesCannotRaiseTheScoreWithUndeclaredCoverage() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/extra", Meta.synthetic().covers("svc:Delete").variants("v-new"));
        Row row = onlyRow(report(c));
        assertThat(row.score()).isEqualTo(1.0);
        assertThat(row.undeclaredOperations()).containsExactly("svc:Delete");
        assertThat(row.undeclaredVariants()).containsExactly("v-new");
        assertThat(report(c).toMarkdown(0.99))
                .contains("Operations cases claim but coverage-spec.yaml does not list: svc:Delete")
                .contains("Variants cases claim but coverage-spec.yaml does not list: v-new");
    }

    @Test
    void supportedVersionBelowTheMinimumFailsTheGate() {
        CoverageReport report = report(withoutWriteCase(TestCorpus.valid(tmp)));
        assertThat(report.gateFailures(0.99))
                .singleElement()
                .asString()
                .contains("fam/env/1")
                .contains("score 0.2500 is below 0.9900")
                .contains("svc:Write");
        assertThat(report.gateFailures(0.2)).isEmpty();
    }

    @Test
    void unsupportedVersionIsReportedButNeverGated() {
        String planned = TestCorpus.VALID_CATALOG.replace("supported: true", "supported: false");
        TestCorpus c = withoutWriteCase(TestCorpus.valid(tmp).write("fam/catalog.yaml", planned));
        CoverageReport report = report(c);
        assertThat(onlyRow(report).claimed()).isTrue();
        assertThat(onlyRow(report).supported()).isFalse();
        assertThat(onlyRow(report).score()).isLessThan(0.99);
        assertThat(report.gateFailures(0.99)).isEmpty();
    }

    @Test
    void supportedVersionMustDocumentOperations() {
        String empty = """
                family: fam
                envelopes:
                  - id: env
                    versions:
                      - id: "1"
                        supported: true
                        operations: []
                        variants: []
                """;
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/coverage-spec.yaml").write("fam/catalog.yaml", empty);
        assertThat(report(c).gateFailures(0.99))
                .anyMatch(f -> f.contains("supported but documents no operations"));
    }

    // ------------------------------------------------------------------
    // The independent denominator
    // ------------------------------------------------------------------

    @Test
    void theCatalogCannotShrinkTheSpecDenominator() {
        // The parser package lists only Read; the spec still counts Read and Write.
        String shrunk = TestCorpus.VALID_CATALOG.replace("[svc:Read, svc:Write]", "[svc:Read]")
                .replace("[v-denied, v-bad-gzip]", "[]");
        TestCorpus c = withoutWriteCase(TestCorpus.valid(tmp).write("fam/catalog.yaml", shrunk));
        Row row = onlyRow(report(c));
        assertThat(row.operationsDocumented()).isEqualTo(2);
        assertThat(row.variantsDocumented()).isEqualTo(2);
        assertThat(row.missingOperations()).containsExactly("svc:Write");
        assertThat(report(c).gateFailures(0.99)).singleElement().asString().contains("below 0.9900");
    }

    @Test
    void supportedRowCountedAgainstItsOwnCatalogFailsTheGate() {
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/coverage-spec.yaml");
        Row row = onlyRow(report(c));
        assertThat(row.denominator()).isEqualTo(Denominator.CATALOG);
        assertThat(row.score()).isEqualTo(1.0);
        assertThat(report(c).gateFailures(0.99)).singleElement().asString()
                .contains("fam/env/1")
                .contains("counted against the parser's own catalog.yaml")
                .contains("fam/coverage-spec.yaml");
    }

    @Test
    void aSelftestFlagInAParserCatalogExemptsNothing() {
        // catalog.yaml is written by parser and fixture packages, so the flag cannot open the exemption.
        String flagged = TestCorpus.VALID_CATALOG.replace("family: fam\n", "family: fam\nselftest: true\n");
        TestCorpus s = TestCorpus.valid(tmp).delete("fam/coverage-spec.yaml").write("fam/catalog.yaml", flagged);
        assertThat(FixtureCatalog.scan(s.root()).problems())
                .anyMatch(p -> p.startsWith("fam/catalog.yaml: 'selftest: true' is reserved for the tooling family 'selftest'"));

        // A row that carries the flag anyway is gated by its family name, like any other parser row.
        TestCorpus plain = TestCorpus.valid(tmp.resolve("plain")).delete("fam/coverage-spec.yaml");
        Row row = onlyRow(report(plain));
        assertThat(row.selftest()).isFalse();
        Row forged = new Row(row.family(), row.envelope(), row.version(), row.denominator(), row.claimed(),
                row.supported(), true, row.operations(), row.variants(), row.confirmedOperations(),
                row.confirmedVariants(), row.driftKillRatio(), row.score(), row.cases(),
                row.undeclaredOperations(), row.undeclaredVariants(), row.excludedOnlyOperations(),
                row.excludedOnlyVariants());
        assertThat(new CoverageReport(List.of(forged), List.of(), List.of()).gateFailures(0.99)).singleElement()
                .asString().contains("fam/env/1").contains("counted against the parser's own catalog.yaml");
    }

    @Test
    void onlyTheToolingFamilyMayBeGatedAgainstItsOwnCatalog() {
        String tooling = TestCorpus.VALID_CATALOG.replace("family: fam\n", "family: selftest\nselftest: true\n");
        TestCorpus t = new TestCorpus(tmp).write("selftest/catalog.yaml", tooling)
                .write("selftest/NOTICE.md", TestCorpus.SYNTHETIC_NOTICE)
                .caseAt("selftest/env/1/read", Meta.synthetic().covers("svc:Read"))
                .caseAt("selftest/env/1/write", Meta.synthetic().covers("svc:Write").variants("v-denied"))
                .caseAt("selftest/negative/env/1/bad-gzip", Meta.synthetic().covers().variants("v-bad-gzip")
                        .outcome("QUARANTINED:INVALID_ENCODING"));
        CoverageReport report = report(t);
        Row row = onlyRow(report);
        assertThat(row.family()).isEqualTo(FixtureCatalog.SELFTEST_FAMILY);
        assertThat(row.denominator()).isEqualTo(Denominator.CATALOG);
        assertThat(row.selftest()).isTrue();
        assertThat(row.supported()).isTrue();
        assertThat(report.gateFailures(0.99)).isEmpty();

        // The exemption covers only the self-grading rule; the score is still gated.
        TestCorpus partial = withoutWriteCaseOf(t, "selftest");
        assertThat(report(partial).gateFailures(0.99)).singleElement().asString()
                .contains("selftest/env/1").contains("below 0.9900");
    }

    @Test
    void aClaimedCellTheSpecDoesNotHaveIsSelfGradedAndFailsWhenSupported() {
        String extra = TestCorpus.VALID_CATALOG + """
                      - id: "2"
                        supported: true
                        operations: [svc:Read]
                        variants: []
                """;
        TestCorpus c = TestCorpus.valid(tmp).write("fam/catalog.yaml", extra)
                .caseAt("fam/env/2/read", Meta.synthetic().covers("svc:Read"));
        CoverageReport report = report(c);
        assertThat(report.rows()).extracting(Row::version).containsExactly("1", "2");
        Row v2 = report.rows().get(1);
        assertThat(v2.denominator()).isEqualTo(Denominator.CATALOG);
        assertThat(v2.score()).isEqualTo(1.0);
        assertThat(report.gateFailures(0.99)).singleElement().asString()
                .contains("fam/env/2").contains("counted against the parser's own catalog.yaml");
    }

    @Test
    void specCellsNobodyClaimsAreShownWithADashAndNeverGated() {
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/catalog.yaml").delete("fam/NOTICE.md");
        for (String dir : List.of("fam/env/1/read", "fam/env/1/write", "fam/negative/env/1/bad-gzip")) {
            c.delete(dir + "/meta.yaml").delete(dir + "/input.json").delete(dir + "/expected.json");
        }
        CoverageReport report = report(c);
        Row row = onlyRow(report);
        assertThat(row.claimed()).isFalse();
        assertThat(row.supported()).isFalse();
        assertThat(row.caseTotal()).isZero();
        assertThat(report.gateFailures(0.99)).isEmpty();
        assertThat(report.toMarkdown(0.99))
                .contains("| fam | env | 1 | spec | - | 0/2 | 0/2 | 0/2 | 0/2 | 0/0/0/0 | n/a | 0.0000 |")
                .contains("No cases yet: 2 documented operations and 2 documented variants are open.");
    }

    @Test
    void unverifiedAndDeferredSpecEntriesAreListedButNotScored() {
        String spec = TestCorpus.VALID_SPEC + """
                  - id: v-maybe
                    title: Not yet confirmed
                    doc_url: https://example.org/maybe
                    status: unverified
                    reason: needs a capture
                """;
        spec = spec.replace("""
                  - id: svc:Write
                    doc_url: https://example.org/write
                """, """
                  - id: svc:Write
                    doc_url: https://example.org/write
                  - id: svc:Later
                    doc_url: https://example.org/later
                    status: deferred
                    reason: a recorded decision
                """);
        CoverageReport report = report(TestCorpus.valid(tmp).write("fam/coverage-spec.yaml", spec));
        Row row = onlyRow(report);
        assertThat(row.operationsDocumented()).isEqualTo(2);
        assertThat(row.variantsDocumented()).isEqualTo(2);
        assertThat(row.score()).isEqualTo(1.0);
        assertThat(report.notScored()).extracting(CoverageReport.NotScored::id).containsExactly("svc:Later", "v-maybe");
        assertThat(report.toMarkdown(0.99))
                .contains("## Listed in a spec but not scored")
                .contains("| fam | operation | svc:Later | deferred | a recorded decision |")
                .contains("| fam | variant | v-maybe | unverified | needs a capture |");
    }

    @Test
    void aNegativeSpecVariantNeedsANegativeCase() {
        // v-bad-gzip is negative in the spec; a positive case claiming it does not cover it.
        TestCorpus c = TestCorpus.valid(tmp)
                .delete("fam/negative/env/1/bad-gzip/meta.yaml")
                .delete("fam/negative/env/1/bad-gzip/input.json")
                .delete("fam/negative/env/1/bad-gzip/expected.json")
                .caseAt("fam/env/1/read", Meta.synthetic().covers("svc:Read").variants("v-bad-gzip"));
        Row row = onlyRow(report(c));
        assertThat(row.missingVariants()).containsExactly("v-bad-gzip");
        assertThat(row.undeclaredVariants()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Evidence classes
    // ------------------------------------------------------------------

    @Test
    void emulatorCapturedCasesCountTowardNothing() {
        TestCorpus c = withoutWriteCase(TestCorpus.valid(tmp))
                .caseAt("fam/env/1/write-emu", Meta.synthetic().emulator().covers("svc:Write").variants("v-denied"));
        Row row = onlyRow(report(c));
        assertThat(row.caseCount(Evidence.EMULATOR)).isEqualTo(1);
        assertThat(row.missingOperations()).containsExactly("svc:Write");
        assertThat(row.missingVariants()).containsExactly("v-denied");
        assertThat(row.excludedOnlyOperations()).containsExactly("svc:Write");
        assertThat(row.excludedOnlyVariants()).containsExactly("v-denied");
        assertThat(row.score()).isCloseTo(0.25, within(1e-12));
        assertThat(report(c).toMarkdown(0.99))
                .contains("Operations only emulator-captured cases claim (not counted): svc:Write")
                .contains("Variants only emulator-captured or generator-only cases claim (not counted): v-denied");
    }

    @Test
    void generatorOnlyCasesCountTowardOperationsButNeverVariants() {
        TestCorpus c = withoutWriteCase(TestCorpus.valid(tmp))
                .caseAt("fam/env/1/write-gen", Meta.synthetic().generator("com.dlap.fixtures.gen.fam.FamGenerator")
                        .covers("svc:Write").variants("v-denied"));
        Row row = onlyRow(report(c));
        assertThat(row.caseCount(Evidence.GENERATED)).isEqualTo(1);
        assertThat(row.missingOperations()).isEmpty();
        assertThat(row.missingVariants()).containsExactly("v-denied");
        assertThat(row.excludedOnlyVariants()).containsExactly("v-denied");
        assertThat(row.excludedOnlyOperations()).isEmpty();
        assertThat(row.score()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void aConfirmedGeneratedCaseIsNotGeneratorOnly() {
        TestCorpus c = withoutWriteCase(TestCorpus.valid(tmp))
                .caseAt("fam/env/1/write-gen", Meta.synthetic().generator("com.dlap.fixtures.gen.fam.FamGenerator")
                        .confirmedBy("proto-round-trip", TEST_EVIDENCE)
                        .covers("svc:Write").variants("v-denied"));
        Row row = onlyRow(report(c));
        assertThat(row.caseCount(Evidence.CONFIRMED)).isEqualTo(1);
        assertThat(row.caseCount(Evidence.GENERATED)).isZero();
        assertThat(row.score()).isEqualTo(1.0);
    }

    @Test
    void confirmedCoverageIsReportedSeparatelyFromTheScore() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/read-captured", Meta.synthetic().ownAccount().covers("svc:Read"))
                .caseAt("fam/env/1/write-rt", Meta.synthetic().confirmedBy("proto-round-trip", TEST_EVIDENCE)
                        .covers("svc:Write").variants("v-denied"));
        CoverageReport report = report(c);
        Row row = onlyRow(report);
        assertThat(row.score()).isEqualTo(1.0);
        assertThat(row.confirmedOperations().covered()).isEqualTo(2);
        assertThat(row.confirmedVariants().covered()).isEqualTo(1);
        assertThat(row.confirmedVariants().missing()).containsExactly("v-bad-gzip");
        assertThat(row.caseCount(Evidence.CONFIRMED)).isEqualTo(2);
        assertThat(row.caseCount(Evidence.AUTHORED)).isEqualTo(3);

        assertThat(report.confirmedCases()).extracting(CoverageReport.ConfirmedCase::id)
                .containsExactly("fam/env/1/read-captured", "fam/env/1/write-rt");
        assertThat(report.confirmedCases().get(0).methods()).containsExactly("own-account-capture");
        assertThat(report.confirmedCases().get(0).evidence()).containsExactly("captured 2026-10-18");
        assertThat(report.confirmedCases().get(1).evidence()).containsExactly(TEST_EVIDENCE);
        assertThat(report.toMarkdown(0.99))
                .contains("| fam | env | 1 | spec | yes | 2/2 | 2/2 | 2/2 | 1/2 | 2/3/0/0 | n/a | 1.0000 |")
                .contains("## Independently confirmed cases")
                .contains("| fam/env/1/write-rt | proto-round-trip | " + TEST_EVIDENCE + " |");
    }

    @Test
    void authoredCasesAreNotConfirmed() {
        CoverageReport report = report(TestCorpus.valid(tmp));
        assertThat(onlyRow(report).confirmedOperations().covered()).isZero();
        assertThat(report.confirmedCases()).isEmpty();
        assertThat(report.toMarkdown(0.99)).contains("None yet.");
    }

    // ------------------------------------------------------------------
    // Rendering and the shipped corpus
    // ------------------------------------------------------------------

    @Test
    void markdownAndJsonRenderEveryRow() throws Exception {
        CoverageReport report = report(TestCorpus.valid(tmp));
        String md = report.toMarkdown(0.99);
        assertThat(md)
                .contains("# Fixture coverage")
                .contains("| fam | env | 1 | spec | yes | 2/2 | 2/2 | 0/2 | 0/2 | 0/3/0/0 | n/a | 1.0000 |")
                .doesNotContain("## Gaps");
        JsonNode json = new ObjectMapper().readTree(report.toJson(0.99));
        assertThat(json.get("minScore").asDouble()).isEqualTo(0.99);
        assertThat(json.get("rows")).hasSize(1);
        JsonNode row = json.get("rows").get(0);
        assertThat(row.get("score").asDouble()).isEqualTo(1.0);
        assertThat(row.get("driftKillRatio").isNull()).isTrue();
        assertThat(row.get("denominator").asText()).isEqualTo("SPEC");
        assertThat(row.get("operations").get("covered").asInt()).isEqualTo(2);
        assertThat(row.get("cases").get("AUTHORED").asInt()).isEqualTo(3);
        assertThat(json.get("confirmedCases")).isEmpty();
        assertThat(json.get("notScored")).isEmpty();
    }

    @Test
    void shippedCorpusGatesTheSelftestFamilyAndShowsEverySpecCell() {
        FixtureCatalog shipped = FixtureCatalog.fromClasspath();
        CoverageReport report = CoverageReport.compute(shipped);

        List<Row> selftest = report.rows().stream().filter(r -> r.family().equals("selftest")).toList();
        assertThat(selftest).extracting(Row::version).containsExactly("1", "2");
        assertThat(selftest.get(0).supported()).isTrue();
        assertThat(selftest.get(0).score()).isEqualTo(1.0);
        assertThat(selftest.get(1).supported()).isFalse();
        assertThat(selftest.get(1).score()).isLessThan(0.99);

        // Every spec cell has exactly one spec-denominated row. A family's catalog may add cells the
        // spec does not have (seed corpora, planned versions); those are catalog rows and are not counted here.
        for (CoverageSpec spec : shipped.specs()) {
            List<String> specRows = report.rows().stream()
                    .filter(r -> r.family().equals(spec.family()) && r.denominator() == Denominator.SPEC)
                    .map(r -> r.envelope() + "/" + r.version())
                    .toList();
            assertThat(specRows).as(spec.family())
                    .containsExactlyElementsOf(spec.cells().stream().map(c -> c.envelope() + "/" + c.version()).toList());
        }
        assertThat(report.gateFailures(CoverageReport.DEFAULT_MIN_SCORE)).isEmpty();
        assertThat(report.toMarkdown(CoverageReport.DEFAULT_MIN_SCORE)).contains("## Gaps").contains("demo:write");
    }
}
