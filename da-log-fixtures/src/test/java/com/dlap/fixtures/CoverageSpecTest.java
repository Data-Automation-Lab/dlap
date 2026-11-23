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

import com.dlap.fixtures.CoverageSpec.Cell;
import com.dlap.fixtures.CoverageSpec.Entry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageSpecTest {

    @TempDir
    Path tmp;

    private static final String TWO_CELLS = """
            spec: dlap-coverage-spec/1
            family: fam
            title: Two envelopes, two versions
            source_kind: TEST_KIND
            written_by: TEST
            written_on: "2026-10-19"
            research: [a test]
            operation_id_rule: "svc:<operation>"
            version_rule: fixed
            envelopes:
              - id: file
                title: Files
                doc_url: https://example.org/file
                versions: ["1", "2"]
              - id: api
                title: API
                doc_url: https://example.org/api
                versions: ["2"]
              - id: later
                title: Not confirmed yet
                doc_url: https://example.org/later
                versions: ["2"]
                status: unverified
                reason: needs a capture
            versions:
              - id: "1"
                title: One
                doc_url: https://example.org/1
                detect: v1
              - id: "2"
                title: Two
                doc_url: https://example.org/2
                detect: v2
                required_fields: [a, "b or c"]
            operations:
              - id: svc:Read
                expected_category: DATA_READ
                doc_url: https://example.org/read
              - id: svc:FileOnly
                envelopes: [file]
                doc_url: https://example.org/file-only
              - id: svc:New
                versions: ["2"]
                doc_url: https://example.org/new
            variants:
              - id: v.any
                title: Applies everywhere
                doc_url: https://example.org/any
              - id: v.quarantine
                title: Must be quarantined
                negative: true
                versions: ["1"]
                doc_url: https://example.org/q
            """;

    private List<String> problems(String yaml) {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/coverage-spec.yaml", yaml);
        return FixtureCatalog.scan(c.root()).problems();
    }

    private CoverageSpec load(String yaml) {
        TestCorpus c = new TestCorpus(tmp.resolve("only")).write("fam/coverage-spec.yaml", yaml);
        FixtureCatalog catalog = FixtureCatalog.scan(c.root());
        assertThat(catalog.problems()).isEmpty();
        return catalog.spec("fam").orElseThrow();
    }

    private static List<String> ids(List<Entry> entries) {
        return entries.stream().map(Entry::id).toList();
    }

    @Test
    void cellsAreEveryDocumentedEnvelopeTimesItsVersions() {
        CoverageSpec spec = load(TWO_CELLS);
        assertThat(spec.family()).isEqualTo("fam");
        assertThat(spec.writtenBy()).isEqualTo("TEST");
        assertThat(spec.writtenOn()).hasToString("2026-10-19");
        assertThat(spec.cells()).containsExactly(new Cell("file", "1"), new Cell("file", "2"), new Cell("api", "2"));
        assertThat(spec.hasCell("later", "2")).as("an unverified envelope has no cells").isFalse();
        assertThat(spec.versions().get(1).requiredFields()).containsExactly("a", "b or c");
    }

    @Test
    void entriesNarrowByVersionAndEnvelope() {
        CoverageSpec spec = load(TWO_CELLS);
        assertThat(ids(spec.operations("file", "1"))).containsExactly("svc:Read", "svc:FileOnly");
        assertThat(ids(spec.operations("file", "2"))).containsExactly("svc:Read", "svc:FileOnly", "svc:New");
        assertThat(ids(spec.operations("api", "2"))).containsExactly("svc:Read", "svc:New");
        assertThat(ids(spec.variants("file", "1"))).containsExactly("v.any", "v.quarantine");
        assertThat(ids(spec.variants("api", "2"))).containsExactly("v.any");
        Entry quarantine = spec.variants().get(1);
        assertThat(quarantine.negative()).isTrue();
        assertThat(quarantine.scored()).isTrue();
        assertThat(spec.operations().get(0).expectedCategory()).isEqualTo("DATA_READ");
    }

    @Test
    void theValidTestSpecHasNoProblems() {
        assertThat(problems(TestCorpus.VALID_SPEC)).isEmpty();
        assertThat(problems(TWO_CELLS)).isEmpty();
    }

    @Test
    void headerProblemsAreReported() {
        String bad = TWO_CELLS
                .replace("spec: dlap-coverage-spec/1", "spec: dlap-coverage-spec/9")
                .replace("family: fam", "family: other")
                .replace("written_on: \"2026-10-19\"", "written_on: \"19 October\"")
                .replace("research: [a test]", "research: []\nextra: 1");
        assertThat(problems(bad))
                .anyMatch(p -> p.contains("'spec' must be 'dlap-coverage-spec/1'"))
                .anyMatch(p -> p.contains("family 'other' does not match the directory name 'fam'"))
                .anyMatch(p -> p.contains("'written_on' must be an ISO date"))
                .anyMatch(p -> p.contains("'research' must name at least one source"))
                .anyMatch(p -> p.contains("unknown key 'extra'"));
        assertThat(problems(TWO_CELLS.replace("version_rule: fixed\n", "")))
                .anyMatch(p -> p.contains("missing required key 'version_rule'"));
    }

    @Test
    void everyEntryNeedsAnHttpsDocUrl() {
        assertThat(problems(TWO_CELLS.replace("doc_url: https://example.org/read", "doc_url: http://example.org/read")))
                .anyMatch(p -> p.contains("svc:Read") && p.contains("doc_url must be an https:// URL"));
        assertThat(problems(TWO_CELLS.replace("    doc_url: https://example.org/any\n", "")))
                .anyMatch(p -> p.contains("v.any") && p.contains("missing required key 'doc_url'"));
        assertThat(problems(TWO_CELLS.replace("    doc_url: https://example.org/1\n", "")))
                .anyMatch(p -> p.contains("version 1") && p.contains("missing required key 'doc_url'"));
    }

    @Test
    void referencesMustResolve() {
        assertThat(problems(TWO_CELLS.replace("versions: [\"1\", \"2\"]", "versions: [\"1\", \"3\"]")))
                .anyMatch(p -> p.contains("envelope file") && p.contains("version '3' is not declared"));
        assertThat(problems(TWO_CELLS.replace("envelopes: [file]", "envelopes: [nope]")))
                .anyMatch(p -> p.contains("svc:FileOnly") && p.contains("envelope 'nope' is not declared"));
        assertThat(problems(TWO_CELLS.replace("    versions: [\"2\"]\n    doc_url: https://example.org/new",
                "    versions: [\"9\"]\n    doc_url: https://example.org/new")))
                .anyMatch(p -> p.contains("svc:New") && p.contains("version '9' is not declared"));
        assertThat(problems(TWO_CELLS.replace("envelopes: [file]", "envelopes: []")))
                .anyMatch(p -> p.contains("applies to nothing"));
    }

    @Test
    void idsAreShapedAndUnique() {
        assertThat(problems(TWO_CELLS.replace("id: svc:New", "id: NoColon")))
                .anyMatch(p -> p.contains("'NoColon': id must match"));
        assertThat(problems(TWO_CELLS.replace("id: v.quarantine", "id: v.any")))
                .anyMatch(p -> p.contains("duplicate variant id 'v.any'"));
        assertThat(problems(TWO_CELLS.replace("id: api", "id: negative")))
                .anyMatch(p -> p.contains("'negative' is reserved"));
        assertThat(problems(TWO_CELLS.replace("id: api", "id: file")))
                .anyMatch(p -> p.contains("duplicate envelope id 'file'"));
    }

    @Test
    void statusAndReasonGoTogether() {
        assertThat(problems(TWO_CELLS.replace("    status: unverified\n    reason: needs a capture\n", "    status: unverified\n")))
                .anyMatch(p -> p.contains("status 'unverified' needs a 'reason'"));
        assertThat(problems(TWO_CELLS.replace("    title: Applies everywhere\n",
                "    title: Applies everywhere\n    reason: why\n")))
                .anyMatch(p -> p.contains("'reason' is only for unverified or deferred entries"));
        assertThat(problems(TWO_CELLS.replace("status: unverified", "status: maybe")))
                .anyMatch(p -> p.contains("status 'maybe' is not one of"));
    }

    @Test
    void categoriesComeFromTheLogEventContract() {
        assertThat(problems(TWO_CELLS.replace("expected_category: DATA_READ", "expected_category: READ")))
                .anyMatch(p -> p.contains("expected_category 'READ' is not one of"));
    }

    @Test
    void aScoredCellNeedsADocumentedOperationAndEveryVersionAnEnvelope() {
        String noOps = TWO_CELLS.replace("""
                  - id: svc:Read
                    expected_category: DATA_READ
                    doc_url: https://example.org/read
                """, "").replace("versions: [\"2\"]\n    doc_url: https://example.org/new", "versions: [\"2\"]\n    doc_url: https://example.org/new\n    status: deferred\n    reason: later");
        assertThat(problems(noOps))
                .anyMatch(p -> p.contains("envelope 'api' version '2' has no documented operation"));
        String unused = TWO_CELLS.replace("versions: [\"1\", \"2\"]", "versions: [\"2\"]");
        assertThat(problems(unused)).anyMatch(p -> p.contains("version '1' is listed by no envelope"));
    }

    @Test
    void aBrokenSpecIsAProblemNotACrash() {
        assertThat(problems("spec: [unclosed\n")).anyMatch(p -> p.contains("fam/coverage-spec.yaml: cannot parse YAML"));
        assertThat(problems("- a list\n")).anyMatch(p -> p.contains("fam/coverage-spec.yaml: must be a YAML mapping"));
    }
}
