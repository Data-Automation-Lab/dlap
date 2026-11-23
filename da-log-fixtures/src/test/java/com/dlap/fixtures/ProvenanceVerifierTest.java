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

import com.dlap.fixtures.TestCorpus.Meta;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvenanceVerifierTest {

    @TempDir
    Path tmp;

    private List<String> violations(TestCorpus corpus) {
        return ProvenanceVerifier.verify(corpus.root()).violations();
    }

    @Test
    void shippedCorpusPasses() {
        Path shipped = FixtureCatalog.fromClasspath().root();
        ProvenanceVerifier.Result result = ProvenanceVerifier.verify(shipped);
        assertThat(result.violations()).isEmpty();
        assertThat(result.ok()).isTrue();
        assertThat(result.cases()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void validCorpusPasses() {
        assertThat(violations(TestCorpus.valid(tmp))).isEmpty();
    }

    @Test
    void caseWithoutMetaFails() {
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/env/1/read/meta.yaml");
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/env/1/read") && v.contains("no meta.yaml"));
    }

    @Test
    void caseWithOnlyAnExpectedFileStillNeedsMeta() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/env/1/orphan/expected.json", "{}");
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/env/1/orphan") && v.contains("no meta.yaml"));
    }

    @Test
    void licenseOutsideTheAllowlistFails() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read", Meta.synthetic().set("license", "GPL-3.0"));
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/env/1/read") && v.contains("'GPL-3.0' is not allowed"));
    }

    @Test
    void licenceNamesAreCaseSensitive() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read", Meta.synthetic().set("license", "mit"));
        assertThat(violations(c)).anyMatch(v -> v.contains("'mit' is not allowed"));
    }

    @Test
    void syntheticOriginMustUseTheSyntheticLicense() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read", Meta.synthetic().set("license", "MIT"));
        assertThat(violations(c)).anyMatch(v -> v.contains("must use license 'synthetic'"));
    }

    @Test
    void publicDatasetCannotClaimSynthetic() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().publicDataset("acme/logs", "MIT").set("license", "synthetic"));
        assertThat(violations(c)).anyMatch(v -> v.contains("must carry the dataset's own licence"));
    }

    @Test
    void publicDatasetNeedsNoticeAttribution() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read", Meta.synthetic().publicDataset("acme/logs", "MIT"));
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/NOTICE.md") && v.contains("no attribution for acme/logs (MIT)"));

        c.write("fam/NOTICE.md", "Cases under env/1/read come from acme/logs, MIT licence.\n");
        assertThat(violations(c)).isEmpty();
    }

    @Test
    void publicDatasetNeedsRepoAndCommitAndAnonymisation() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/NOTICE.md", "acme/logs MIT\n")
                .caseAt("fam/env/1/read", Meta.synthetic().publicDataset("acme/logs", "MIT")
                        .without("commit").set("anonymised", "false"));
        assertThat(violations(c))
                .anyMatch(v -> v.contains("public-dataset needs repo and commit"))
                .anyMatch(v -> v.contains("repo and commit go together"))
                .anyMatch(v -> v.contains("requires anonymised: true"));
    }

    @Test
    void commitMustBeTheFullShaAndQuoted() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/NOTICE.md", "acme/logs MIT\n")
                .caseAt("fam/env/1/read", Meta.synthetic().publicDataset("acme/logs", "MIT").set("commit", "\"abc123\""));
        assertThat(violations(c)).anyMatch(v -> v.contains("commit must be the full 40-character lowercase SHA"));

        // An unquoted all-digit SHA is read as a number by YAML and must be rejected, not silently altered.
        TestCorpus d = TestCorpus.valid(tmp.resolve("numeric")).write("fam/NOTICE.md", "acme/logs MIT\n")
                .caseAt("fam/env/1/read", Meta.synthetic().publicDataset("acme/logs", "MIT")
                        .set("commit", "1234567890123456789012345678901234567890"));
        assertThat(violations(d)).anyMatch(v -> v.contains("'commit' must be a string"));
    }

    @Test
    void syntheticDocCaseNeedsSourceUrlAndDate() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().without("source_url").without("doc_date"));
        assertThat(violations(c)).anyMatch(v -> v.contains("needs source_url and doc_date"));
    }

    @Test
    void sourceUrlMustBeHttps() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().set("source_url", "http://example.org/spec"));
        assertThat(violations(c)).anyMatch(v -> v.contains("source_url must be an https:// URL"));
    }

    @Test
    void capturedOriginsNeedACaptureDateAndAnonymisation() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().set("origin", "own-account-redacted").without("source_url").without("doc_date"));
        assertThat(violations(c))
                .anyMatch(v -> v.contains("needs doc_date (the capture date)"))
                .anyMatch(v -> v.contains("requires anonymised: true"));

        TestCorpus ok = TestCorpus.valid(tmp.resolve("ok")).caseAt("fam/env/1/read",
                Meta.synthetic().set("origin", "partner-redacted").without("source_url").set("anonymised", "true"));
        assertThat(violations(ok)).isEmpty();
    }

    @Test
    void emulatorCapturedAcceptsEitherCitation() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().set("origin", "emulator-captured"));
        assertThat(violations(c)).isEmpty();

        TestCorpus none = TestCorpus.valid(tmp.resolve("none")).caseAt("fam/env/1/read",
                Meta.synthetic().set("origin", "emulator-captured").without("source_url").without("doc_date"));
        assertThat(violations(none)).anyMatch(v -> v.contains("emulator-captured needs source_url and doc_date, or repo and commit"));
    }

    @Test
    void neverVendoredSourcesAreBlocked() {
        for (String repo : List.of("elastic/integrations", "grafana/loki", "awsdocs/aws-cloudtrail-user-guide")) {
            Path dir = tmp.resolve(repo.replace('/', '_'));
            TestCorpus c = TestCorpus.valid(dir).write("fam/NOTICE.md", repo + " MIT\n")
                    .caseAt("fam/env/1/read", Meta.synthetic().publicDataset(repo, "MIT"));
            assertThat(ProvenanceVerifier.verify(c.root()).violations())
                    .as(repo)
                    .anyMatch(v -> v.contains("never vendors"));
        }
    }

    @Test
    void flawsCloudIsBlockedInASourceUrlOfCapturedData() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().set("origin", "emulator-captured").set("source_url", "https://flaws.cloud/data"));
        assertThat(violations(c)).anyMatch(v -> v.contains("'flaws.cloud'"));
    }

    @Test
    void aSyntheticCaseMayCiteAVendorDocumentationRepository() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().set("source_url", "https://github.com/MicrosoftDocs/azure-docs/blob/main/x.md"));
        assertThat(violations(c)).isEmpty();
    }

    @Test
    void positiveCaseMustCoverAnOperation() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read", Meta.synthetic().covers());
        assertThat(violations(c)).anyMatch(v -> v.contains("must cover at least one documented service:operation"));
    }

    @Test
    void negativeCaseMustNameItsVariant() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/negative/env/1/bad-gzip", Meta.synthetic().covers().variants());
        assertThat(violations(c)).anyMatch(v -> v.contains("negative case must name the variant"));
    }

    @Test
    void missingInputAndBrokenExpectedFail() {
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/env/1/read/input.json")
                .write("fam/env/1/write/expected.json", "{not json");
        assertThat(violations(c))
                .anyMatch(v -> v.contains("fam/env/1/read") && v.contains("no input.* file"))
                .anyMatch(v -> v.contains("fam/env/1/write") && v.contains("expected.json is not valid JSON"));

        TestCorpus noExpected = TestCorpus.valid(tmp.resolve("e")).delete("fam/env/1/read/expected.json");
        assertThat(violations(noExpected)).anyMatch(v -> v.contains("fam/env/1/read: no expected.json"));
    }

    @Test
    void caseOutsideTheCatalogFails() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/other/1/read", Meta.synthetic());
        assertThat(violations(c)).anyMatch(v -> v.contains("envelope 'other' version '1' is not declared"));

        TestCorpus v2 = TestCorpus.valid(tmp.resolve("v2")).caseAt("fam/env/2/read", Meta.synthetic());
        assertThat(violations(v2)).anyMatch(v -> v.contains("envelope 'env' version '2' is not declared"));
    }

    @Test
    void caseAtTheWrongDepthFails() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/read", Meta.synthetic());
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/env/read") && v.contains("a case must sit at"));
    }

    @Test
    void missingNoticeAndCatalogFail() {
        TestCorpus c = TestCorpus.valid(tmp).delete("fam/NOTICE.md");
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/NOTICE.md: missing"));

        TestCorpus d = TestCorpus.valid(tmp.resolve("d")).delete("fam/catalog.yaml");
        assertThat(violations(d)).anyMatch(v -> v.contains("fam/catalog.yaml: missing"));

        TestCorpus e = TestCorpus.valid(tmp.resolve("e")).write("fam/NOTICE.md", "  \n");
        assertThat(violations(e)).anyMatch(v -> v.contains("fam/NOTICE.md: is empty"));
    }

    @Test
    void unknownKeysAndBadEnumsFail() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read",
                Meta.synthetic().set("licence", "synthetic").set("origin", "made-up"));
        assertThat(violations(c))
                .anyMatch(v -> v.contains("unknown key 'licence'"))
                .anyMatch(v -> v.contains("origin 'made-up' is not one of"));
    }

    @Test
    void missingRequiredKeysAreAllReported() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/env/1/read/meta.yaml", "origin: synthetic-from-doc\n");
        assertThat(violations(c))
                .anyMatch(v -> v.contains("missing required key 'license'"))
                .anyMatch(v -> v.contains("missing required key 'covers'"))
                .anyMatch(v -> v.contains("missing required key 'variant_ids'"))
                .anyMatch(v -> v.contains("missing required key 'expected_outcome'"))
                .anyMatch(v -> v.contains("missing required key 'anonymised'"));
    }

    @Test
    void malformedValuesFail() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/read", Meta.synthetic()
                .set("doc_date", "18/10/2026")
                .set("covers", "[notanoperation]")
                .set("expected_outcome", "parsed")
                .set("anonymised", "maybe"));
        assertThat(violations(c))
                .anyMatch(v -> v.contains("'doc_date' must be an ISO date"))
                .anyMatch(v -> v.contains("'covers' entry") && v.contains("must be a string matching"))
                .anyMatch(v -> v.contains("expected_outcome 'parsed' must look like"))
                .anyMatch(v -> v.contains("'anonymised' must be true or false"));
    }

    @Test
    void duplicateYamlKeysAndDuplicateListEntriesFail() {
        TestCorpus c = TestCorpus.valid(tmp)
                .write("fam/env/1/read/meta.yaml", Meta.synthetic().render() + "license: synthetic\n");
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/env/1/read/meta.yaml") && v.contains("cannot parse YAML"));

        TestCorpus d = TestCorpus.valid(tmp.resolve("d")).caseAt("fam/env/1/read",
                Meta.synthetic().set("covers", "[svc:Read, svc:Read]"));
        assertThat(violations(d)).anyMatch(v -> v.contains("'covers' repeats [svc:Read]"));
    }

    @Test
    void unparseableMetaIsReportedNotThrown() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/env/1/read/meta.yaml", "origin: [unclosed\n");
        assertThat(violations(c)).anyMatch(v -> v.contains("cannot parse YAML"));
    }

    @Test
    void catalogProblemsAreReported() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/catalog.yaml", """
                family: wrong
                envelopes:
                  - id: negative
                    versions:
                      - id: 1.10
                        operations: [svc:Read]
                        variants: []
                  - id: env
                    versions:
                      - id: "1"
                        operations: [svc:Read]
                        variants: []
                      - id: "1"
                        operations: [svc:Read]
                        variants: []
                """);
        assertThat(violations(c))
                .anyMatch(v -> v.contains("family 'wrong' does not match the directory name 'fam'"))
                .anyMatch(v -> v.contains("'negative' is reserved"))
                .anyMatch(v -> v.contains("'id' must be a string (quote it in YAML"))
                .anyMatch(v -> v.contains("duplicate version id '1'"));
    }

    @Test
    void emptyCorpusRootAndMissingRootAreReported() {
        assertThat(ProvenanceVerifier.verify(tmp.resolve("does-not-exist")).violations())
                .anyMatch(v -> v.contains("not a directory"));
    }

    // ------------------------------------------------------------------
    // Evidence rules and coverage specs (SPEC-01)
    // ------------------------------------------------------------------

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void wellFormedConfirmationsPass() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/rt", Meta.synthetic().confirmedBy("proto-round-trip",
                        "com.dlap.logingest.parse.fam.RoundTripTest#everyCase"))
                .caseAt("fam/env/1/lib", Meta.synthetic().confirmedBy("processing-library",
                        "com.dlap.logingest.parse.cloudtrail.ProcessingLibraryDifferentialTest"))
                .caseAt("fam/env/1/otel", Meta.synthetic().confirmedBy("otel-expected",
                        "open-telemetry/opentelemetry-collector-contrib@" + SHA + ":pkg/testdata/storage_expected.yaml"))
                .caseAt("fam/env/1/own", Meta.synthetic().ownAccount().confirmedBy("own-account-capture", "LIVE-01 capture"));
        assertThat(violations(c)).isEmpty();
    }

    @Test
    void confirmationEvidenceMustBeReCheckable() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/rt", Meta.synthetic().confirmedBy("proto-round-trip", "I ran it once"))
                .caseAt("fam/env/1/otel", Meta.synthetic().confirmedBy("otel-expected", "open-telemetry/contrib:main/x.yaml"));
        assertThat(violations(c))
                .anyMatch(v -> v.contains("fam/env/1/rt") && v.contains("must name the test class"))
                .anyMatch(v -> v.contains("fam/env/1/otel") && v.contains("owner/name@<40-hex commit>:<path>"));
    }

    @Test
    void anEmulatorCaptureCannotBeConfirmed() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/emu", Meta.synthetic().emulator()
                .confirmedBy("proto-round-trip", "com.dlap.logingest.parse.fam.RoundTripTest"));
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/env/1/emu") && v.contains("cannot be independently confirmed"));
    }

    @Test
    void captureMethodsNeedTheMatchingOrigin() {
        TestCorpus c = TestCorpus.valid(tmp)
                .caseAt("fam/env/1/a", Meta.synthetic().confirmedBy("own-account-capture", "claimed"))
                .caseAt("fam/env/1/b", Meta.synthetic().confirmedBy("partner-capture", "claimed"));
        assertThat(violations(c))
                .anyMatch(v -> v.contains("fam/env/1/a") && v.contains("own-account-capture needs origin own-account-redacted"))
                .anyMatch(v -> v.contains("fam/env/1/b") && v.contains("partner-capture needs origin partner-redacted"));
    }

    @Test
    void aCaptureOrDatasetIsNeverGeneratorOutput() {
        TestCorpus c = TestCorpus.valid(tmp)
                .write("fam/NOTICE.md", "acme/logs MIT\n")
                .caseAt("fam/env/1/own", Meta.synthetic().ownAccount().generator("com.dlap.fixtures.gen.Gen"))
                .caseAt("fam/env/1/ds", Meta.synthetic().publicDataset("acme/logs", "MIT").generator("com.dlap.fixtures.gen.Gen"))
                .caseAt("fam/env/1/gen", Meta.synthetic().generator("com.dlap.fixtures.gen.Gen"));
        List<String> v = violations(c);
        assertThat(v).anyMatch(x -> x.contains("fam/env/1/own") && x.contains("not generator output"));
        assertThat(v).anyMatch(x -> x.contains("fam/env/1/ds") && x.contains("not generator output"));
        assertThat(v).noneMatch(x -> x.contains("fam/env/1/gen"));
    }

    @Test
    void aRepeatedConfirmationMethodFails() {
        TestCorpus c = TestCorpus.valid(tmp).caseAt("fam/env/1/rt", Meta.synthetic()
                .confirmedBy("proto-round-trip", "com.dlap.a.ATest")
                .confirmedBy("proto-round-trip", "com.dlap.b.BTest"));
        assertThat(violations(c)).anyMatch(v -> v.contains("confirmed_by repeats method proto-round-trip"));
    }

    @Test
    void aSpecOnlyFamilyNeedsNoNoticeYet() {
        TestCorpus c = TestCorpus.valid(tmp).write("next/coverage-spec.yaml",
                TestCorpus.VALID_SPEC.replace("family: fam", "family: next"));
        assertThat(violations(c)).isEmpty();
        // Once it has a catalog, it needs its NOTICE.md like any other family.
        c.write("next/catalog.yaml", TestCorpus.VALID_CATALOG.replace("family: fam", "family: next"));
        assertThat(violations(c)).anyMatch(v -> v.contains("next/NOTICE.md: missing"));
    }

    @Test
    void aBrokenSpecFailsTheProvenanceGate() {
        TestCorpus c = TestCorpus.valid(tmp).write("fam/coverage-spec.yaml",
                TestCorpus.VALID_SPEC.replace("doc_url: https://example.org/read", "doc_url: ftp://example.org/read"));
        assertThat(violations(c)).anyMatch(v -> v.contains("fam/coverage-spec.yaml") && v.contains("https://"));
    }
}
