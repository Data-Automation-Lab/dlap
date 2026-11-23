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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dlap.fixtures.CoverageReport;
import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCase.Origin;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.fixtures.ProvenanceVerifier;
import com.dlap.fixtures.anon.SeedManifest.Source;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the seed cases that FIX-02 imported into the shipped corpus. Every test reads the committed
 * files from the classpath, so none of them needs the source repositories:
 * <ul>
 *   <li>provenance: each case names its repository, commit, licence and date, and they agree with the
 *       pinned {@code seeds.yaml} and the family {@code NOTICE.md};</li>
 *   <li>anonymisation: the leak scanner finds no account id, address or mailbox, and the committed input
 *       is a fixed point of the anonymiser;</li>
 *   <li>honesty: {@code expected.json} and {@code meta.yaml} are exactly what the family rules compute
 *       from the committed input, so nobody edited them by hand;</li>
 *   <li>reporting: the coverage report has a row for every cell a seed case fills.</li>
 * </ul>
 * The one test that needs the source repositories ({@link #committedSeedsMatchAFreshImport}) runs only
 * when the environment variable {@code DLAP_SEED_SOURCES} points at a directory that holds the checkouts.
 */
class SeedCorpusTest {

    /** The families FIX-02 seeds. */
    private static final List<String> SEED_FAMILIES =
            List.of("azactivity", "azstoragelog", "cloudtrail", "dbxaudit", "gcpaudit");

    private static FixtureCatalog corpus;

    @BeforeAll
    static void loadCorpus() {
        corpus = FixtureCatalog.fromClasspath();
        assertThat(corpus.problems()).isEmpty();
    }

    private static SeedManifest manifest(String family) {
        return SeedManifest.read(corpus.root().resolve(family).resolve(SeedManifest.FILE));
    }

    /** The cases of the given family that came from a public dataset, which are the seed cases. */
    private static List<FixtureCase> seedCases(String family) {
        return corpus.cases(family).stream().filter(c -> c.meta().origin() == Origin.PUBLIC_DATASET).toList();
    }

    private static List<FixtureCase> allSeedCases() {
        List<FixtureCase> out = new ArrayList<>();
        SEED_FAMILIES.forEach(f -> out.addAll(seedCases(f)));
        return out;
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------

    @Test
    void everySeedFamilyHasCasesAManifestAndANotice() {
        for (String family : SEED_FAMILIES) {
            assertThat(corpus.familyNames()).as(family).contains(family);
            assertThat(seedCases(family)).as(family + " seed cases").isNotEmpty();
            assertThat(corpus.root().resolve(family).resolve(SeedManifest.FILE)).as(family + " manifest").exists();
            assertThat(corpus.root().resolve(family).resolve(FixtureCatalog.NOTICE_FILE)).as(family + " notice").exists();
            assertThat(manifest(family).family()).isEqualTo(family);
        }
    }

    @Test
    void everyManifestCaseIsInTheCorpusAndEverySeedCaseIsInAManifest() {
        for (String family : SEED_FAMILIES) {
            Set<String> inManifest = manifest(family).cases().stream().map(SeedManifest.Case::name)
                    .collect(Collectors.toSet());
            Set<String> inCorpus = seedCases(family).stream().map(FixtureCase::name).collect(Collectors.toSet());
            assertThat(inCorpus).as(family + ": a seed case that seeds.yaml does not list").isEqualTo(inManifest);
        }
    }

    @Test
    void everySeedCaseCarriesRepoCommitLicenceAndDate() {
        for (FixtureCase c : allSeedCases()) {
            SeedManifest m = manifest(c.family());
            SeedManifest.Case manifestCase = m.cases().stream().filter(x -> x.name().equals(c.name())).findFirst()
                    .orElseThrow();
            Source s = m.sources().get(manifestCase.source());
            assertThat(c.meta().repo()).as(c.id() + " repo").isEqualTo(s.repo());
            assertThat(c.meta().commit()).as(c.id() + " commit").matches("[0-9a-f]{40}").isEqualTo(s.commit());
            assertThat(c.meta().license()).as(c.id() + " licence").isEqualTo(s.license())
                    .isIn("MIT", "Apache-2.0", "BSD-2-Clause");
            assertThat(c.meta().docDate()).as(c.id() + " date").isNotNull().hasToString(s.commitDate());
            assertThat(c.meta().sourceUrl()).as(c.id() + " source_url").startsWith("https://github.com/" + s.repo());
            assertThat(c.meta().anonymised()).as(c.id() + " anonymised").isTrue();
            assertThat(c.meta().notes()).as(c.id() + " notes").startsWith("Anonymised excerpt of " + s.repo() + " at ");
        }
    }

    @Test
    void pantherSamplesAreMarkedDerived() {
        List<FixtureCase> panther = allSeedCases().stream()
                .filter(c -> "panther-labs/panther-analysis".equals(c.meta().repo())).toList();
        assertThat(panther).isNotEmpty();
        assertThat(panther).allSatisfy(c -> assertThat(c.meta().notes()).contains("DERIVED"));
    }

    @Test
    void everyFamilyNoticeAttributesEachSourceItUses() throws Exception {
        for (String family : SEED_FAMILIES) {
            String notice = Files.readString(corpus.root().resolve(family).resolve(FixtureCatalog.NOTICE_FILE),
                    StandardCharsets.UTF_8);
            for (Source s : manifest(family).sources().values()) {
                assertThat(notice).as(family + " NOTICE.md").contains(s.repo()).contains(s.commit())
                        .contains(s.license()).contains(s.retrieved());
            }
        }
        String ct = Files.readString(corpus.root().resolve("cloudtrail").resolve(FixtureCatalog.NOTICE_FILE),
                StandardCharsets.UTF_8);
        assertThat(ct).as("the MIT licence text and copyright line travel with the Invictus data")
                .contains("Copyright (c) 2023 Invictus Incident Response")
                .contains("Permission is hereby granted, free of charge");
    }

    @Test
    void theProvenanceGatePassesOnTheWholeCorpus() {
        ProvenanceVerifier.Result result = ProvenanceVerifier.verify(corpus);
        assertThat(result.violations()).isEmpty();
        assertThat(result.cases()).isGreaterThanOrEqualTo(allSeedCases().size());
    }

    // ------------------------------------------------------------------
    // Anonymisation
    // ------------------------------------------------------------------

    @Test
    void noRealAccountIdAddressOrMailboxIsInTheCorpus() {
        assertThat(LeakScanner.scanTree(corpus.root())).isEmpty();
    }

    @Test
    void noCredentialShapedValueIsInTheCorpus() {
        // Q-220: a temporary credential from a public dataset is expired, but it still fails a secret scan.
        assertThat(LeakScanner.scanTree(corpus.root())).filteredOn(f -> f.kind() == LeakScanner.Kind.CREDENTIAL).isEmpty();
    }

    @Test
    void theCommittedInputIsAFixedPointOfTheAnonymiser() {
        Anonymiser anon = Anonymiser.standard();
        for (FixtureCase c : allSeedCases()) {
            for (JsonNode doc : documentsOf(c)) {
                Anonymiser.Result again = anon.anonymise(doc);
                assertThat(SeedJson.compact(again.tree())).as(c.id() + " changes when anonymised again")
                        .isEqualTo(SeedJson.compact(doc));
                assertThat(again.replaced()).as(c.id()).isEmpty();
            }
        }
    }

    /** The JSON documents of a case's input: one, or one per line for JSON Lines. */
    private static List<JsonNode> documentsOf(FixtureCase c) {
        String text = c.inputText();
        if (c.primaryInput().getFileName().toString().contains(".jsonl")) {
            return text.lines().filter(l -> !l.isBlank()).map(SeedJson::parse).toList();
        }
        return List.of(SeedJson.parse(text));
    }

    // ------------------------------------------------------------------
    // Honesty: expected.json and meta.yaml follow from the input
    // ------------------------------------------------------------------

    @Test
    void expectedResultsAndMetaFollowFromTheCommittedInput() {
        for (FixtureCase c : allSeedCases()) {
            FamilyRules rules = FamilyRules.of(c.family());
            assertThat(c.envelope()).as(c.id() + " envelope").isEqualTo(rules.envelope());
            if (c.negative()) {
                String[] outcome = c.meta().expectedOutcome().split(":", 2);
                JsonNode expected = c.expectedTree();
                assertThat(expected.get("outcome").asText()).as(c.id()).isEqualTo(outcome[0]);
                assertThat(expected.path("reason").asText(null)).as(c.id()).isEqualTo(outcome.length > 1 ? outcome[1] : null);
                assertThat(expected.get("events")).as(c.id()).isEmpty();
                continue;
            }
            List<JsonNode> records = rules.unwrap(rules.inputExtension().equals("jsonl")
                    ? arrayOf(documentsOf(c)) : documentsOf(c).get(0));
            SeedImporter.Derived d = SeedImporter.derive(rules, records, c.primaryInput().toString().endsWith(".gz"));
            assertThat(c.version()).as(c.id() + " version cell").isEqualTo(d.version());
            assertThat(c.meta().expectedOutcome()).as(c.id()).isEqualTo("PARSED");
            assertThat(c.expectedJson()).as(c.id() + " expected.json").isEqualTo(SeedImporter.expectedPositiveText(d.events()));
            assertThat(c.meta().covers()).as(c.id() + " covers").containsExactlyElementsOf(d.covers());
            assertThat(c.meta().variantIds()).as(c.id() + " variant_ids").containsExactlyElementsOf(d.variants());
        }
    }

    @Test
    void everyCloudTrailSeedCaseHasOneEventIdPerRecord() {
        // Q-642: the three Panther samples that reused one eventID carry distinct_event_ids: true.
        SeedManifest m = manifest("cloudtrail");
        FamilyRules rules = FamilyRules.of("cloudtrail");
        int marked = 0;
        for (FixtureCase c : seedCases("cloudtrail")) {
            if (c.negative()) {
                continue;
            }
            List<JsonNode> records = rules.unwrap(documentsOf(c).get(0));
            List<String> ids = records.stream().map(r -> r.path("eventID").asText()).toList();
            assertThat(ids).as(c.id() + " eventIDs").doesNotHaveDuplicates();
            SeedManifest.Case mc = m.cases().stream().filter(x -> x.name().equals(c.name())).findFirst().orElseThrow();
            if (mc.distinctEventIds()) {
                marked++;
                for (int i = 0; i < ids.size(); i++) {
                    assertThat(ids.get(i)).as(c.id() + " record " + i).isEqualTo(java.util.UUID.nameUUIDFromBytes(
                            ("cloudtrail/" + c.name() + "|" + i).getBytes(StandardCharsets.UTF_8)).toString());
                }
                assertThat(c.meta().notes()).as(c.id()).contains("Q-642");
            }
        }
        assertThat(marked).isEqualTo(3);
    }

    /** Lines of a JSON Lines input back into the array that {@code FamilyRules#unwrap} expects. */
    private static JsonNode arrayOf(List<JsonNode> lines) {
        var array = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        lines.forEach(array::add);
        return array;
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    @Test
    void theCoverageReportListsEveryCellTheSeedsFill() {
        CoverageReport report = CoverageReport.compute(corpus);
        String markdown = report.toMarkdown(CoverageReport.DEFAULT_MIN_SCORE);
        Map<String, CoverageReport.Row> rows = report.rows().stream().collect(Collectors.toMap(
                r -> r.family() + "/" + r.envelope() + "/" + r.version(), r -> r, (a, b) -> a));
        for (FixtureCase c : allSeedCases()) {
            if (c.negative()) {
                continue;
            }
            CoverageReport.Row row = rows.get(c.family() + "/" + c.envelope() + "/" + c.version());
            assertThat(row).as(c.id() + " has a coverage row").isNotNull();
            // every operation a case claims is either a documented one it covers or listed as undeclared
            assertThat(row.operationsCovered() + row.undeclaredOperations().size())
                    .as(row.family() + "/" + row.version()).isPositive();
            assertThat(markdown).contains("| " + c.family() + " | " + c.envelope() + " | " + c.version() + " |");
        }
    }

    @Test
    void theListCommandPrintsEverySeedWithItsSource() {
        Path sources = Path.of("src", "main", "resources", "dlap-fixtures").toAbsolutePath();
        assumeTrue(Files.isDirectory(sources), "run from the da-log-fixtures module directory");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeedImporter.run(new String[] {"list", "--corpus", sources.toString()},
                new PrintStream(out), new PrintStream(err));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines.get(0)).isEqualTo("case\trepo\tcommit\tlicence\tdate\toperations\tvariants");
        assertThat(lines).hasSize(1 + allSeedCases().size());
        assertThat(lines.subList(1, lines.size())).allSatisfy(l -> {
            String[] cols = l.split("\t");
            assertThat(cols).hasSize(7);
            assertThat(cols[2]).matches("[0-9a-f]{12}");
            assertThat(cols[3]).isIn("MIT", "Apache-2.0", "BSD-2-Clause");
            assertThat(cols[4]).matches("\\d{4}-\\d{2}-\\d{2}");
        });
    }

    // ------------------------------------------------------------------
    // Repeatability against the pinned sources
    // ------------------------------------------------------------------

    @Test
    void committedSeedsMatchAFreshImport() {
        String dir = System.getenv("DLAP_SEED_SOURCES");
        assumeTrue(dir != null && !dir.isBlank(), "set DLAP_SEED_SOURCES to a directory holding the pinned checkouts");
        Path base = Path.of(dir);
        // The classpath corpus may sit inside a jar, so check the source tree (Gradle runs tests in the module directory).
        Path sources = Path.of("src", "main", "resources", "dlap-fixtures").toAbsolutePath();
        assumeTrue(Files.isDirectory(sources), "run from the da-log-fixtures module directory");
        List<String> args = new ArrayList<>(List.of("check", "--corpus", sources.toString()));
        for (String id : List.of("invictus", "panther", "pservices", "cloudevents", "otel")) {
            args.add("--source");
            args.add(id + "=" + base.resolve(id));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeedImporter.run(args.toArray(String[]::new), new PrintStream(out), new PrintStream(err));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
    }
}
