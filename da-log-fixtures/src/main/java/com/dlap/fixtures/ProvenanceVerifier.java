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

import com.dlap.fixtures.FixtureCase.Confirmation;
import com.dlap.fixtures.FixtureCase.ConfirmationMethod;
import com.dlap.fixtures.FixtureCase.Meta;
import com.dlap.fixtures.FixtureCase.Origin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The provenance gate for the fixture corpus (Gradle task {@code verifyFixtureProvenance}).
 *
 * <p>It fails on any case that has no {@code meta.yaml}, whose licence is outside the allowlist,
 * whose origin and licence disagree, that lacks the citation its origin needs, that comes from a
 * source the project never vendors, or whose family does not attribute a third-party source in its
 * {@code NOTICE.md}. It also fails on every structural problem {@link FixtureCatalog#scan} found.
 *
 * <p>Licence rules, from the hardening plan (decision D8):
 * <ul>
 *   <li>{@code synthetic} means no third-party licence applies: written by the project or captured
 *       from the project's own account;</li>
 *   <li>a public dataset keeps its own licence, which must be Apache-2.0, MIT or BSD-2-Clause, and
 *       the family {@code NOTICE.md} must name its repository and licence;</li>
 *   <li>vendor-doc samples, Elastic content, flaws.cloud data and unredacted captures are never
 *       vendored; the gate blocks the known sources and requires {@code anonymised: true} on every
 *       dataset or capture.</li>
 * </ul>
 *
 * <p>Evidence rules, so a case cannot claim more independence than it has (SPEC-01):
 * <ul>
 *   <li>an emulator capture is never independently confirmed;</li>
 *   <li>{@code own-account-capture} and {@code partner-capture} only go with the matching redacted
 *       origin, and a capture or public dataset is never generator output;</li>
 *   <li>every {@code confirmed_by} entry names checkable evidence: the test class that runs the proto
 *       round-trip or the processing library, or {@code owner/name@<40-hex commit>:<path>} of the
 *       independent expected output.</li>
 * </ul>
 */
public final class ProvenanceVerifier {

    /** The licence value for content the project owns or generated. */
    public static final String SYNTHETIC = "synthetic";

    /** Licences a fixture may carry. */
    public static final Set<String> ALLOWED_LICENSES = Set.of(SYNTHETIC, "Apache-2.0", "MIT", "BSD-2-Clause");

    /**
     * Source names that must never appear in {@code repo} or {@code source_url} of a case
     * (compared in lower case). They are the sources the plan excludes: Elastic integrations
     * (Elastic License 2.0), flaws.cloud (no licence), Grafana Loki (AGPL) and the vendor
     * documentation sample repositories (licences disputed).
     */
    public static final Set<String> BANNED_SOURCES = Set.of(
            "elastic/integrations", "flaws.cloud", "grafana/loki", "awsdocs/", "microsoftdocs/");

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /** Evidence for a check a test runs: a fully qualified test class, optionally {@code #method}. */
    static final Pattern TEST_EVIDENCE = Pattern.compile("([a-z_][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(#[A-Za-z0-9_]+)?");

    /** Evidence held in another repository: {@code owner/name@<40-hex commit>:<path>}. */
    static final Pattern REPO_EVIDENCE = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}:\\S+");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProvenanceVerifier() {
    }

    /**
     * The outcome of a verification run.
     *
     * @param violations every rule that failed, one line each; empty when the corpus passes
     * @param cases      how many cases were read
     * @param families   how many family directories exist
     */
    public record Result(List<String> violations, int cases, int families) {

        /**
         * Tells whether the corpus passed.
         *
         * @return true when there are no violations
         */
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    /**
     * Scans a corpus directory and verifies it.
     *
     * @param root the corpus root
     * @return the result
     */
    public static Result verify(Path root) {
        return verify(FixtureCatalog.scan(root));
    }

    /**
     * Verifies an already scanned corpus.
     *
     * @param catalog the corpus
     * @return the result
     */
    public static Result verify(FixtureCatalog catalog) {
        List<String> violations = new ArrayList<>(catalog.problems());
        for (FixtureCase c : catalog.cases()) {
            verifyCase(catalog, c, violations);
        }
        for (String family : catalog.familyNames()) {
            verifyNotice(catalog, family, violations);
        }
        return new Result(List.copyOf(violations), catalog.cases().size(), catalog.familyNames().size());
    }

    private static void verifyCase(FixtureCatalog catalog, FixtureCase c, List<String> out) {
        Meta m = c.meta();
        String id = c.id();

        if (!ALLOWED_LICENSES.contains(m.license())) {
            out.add(id + ": license '" + m.license() + "' is not allowed; use one of " + new TreeSet<>(ALLOWED_LICENSES));
        }
        verifyOriginRules(id, m, out);
        verifyCitation(id, m, out);
        verifyEvidence(id, m, out);

        // A synthetic case may cite any documentation as the schema it was written from; the ban is on
        // vendoring content, so there only the repo is checked.
        boolean citesOnly = m.origin() == Origin.SYNTHETIC_FROM_DOC || m.origin() == Origin.SYNTHETIC_FROM_PROTO;
        String source = ((m.repo() == null ? "" : m.repo()) + " "
                + (m.sourceUrl() == null || citesOnly ? "" : m.sourceUrl())).toLowerCase(Locale.ROOT);
        for (String banned : BANNED_SOURCES) {
            if (source.contains(banned)) {
                out.add(id + ": source mentions '" + banned + "', which the project never vendors");
            }
        }

        if (!c.negative() && m.covers().isEmpty()) {
            out.add(id + ": a positive case must cover at least one documented service:operation");
        }
        if (c.negative() && m.covers().isEmpty() && m.variantIds().isEmpty()) {
            out.add(id + ": a negative case must name the variant it exercises in variant_ids");
        }

        if (c.inputFiles().isEmpty()) {
            out.add(id + ": no input.* file");
        }
        verifyExpected(c, out);

        Optional<FixtureCatalog.Family> family = catalog.family(c.family());
        if (family.isPresent() && family.get().version(c.envelope(), c.version()).isEmpty()) {
            out.add(id + ": envelope '" + c.envelope() + "' version '" + c.version() + "' is not declared in "
                    + c.family() + "/" + FixtureCatalog.CATALOG_FILE + "; add it so the case counts toward a score");
        }
    }

    private static void verifyOriginRules(String id, Meta m, List<String> out) {
        boolean synthetic = SYNTHETIC.equals(m.license());
        switch (m.origin()) {
            case SYNTHETIC_FROM_DOC, SYNTHETIC_FROM_PROTO, OWN_ACCOUNT_REDACTED, PARTNER_REDACTED -> {
                if (!synthetic) {
                    out.add(id + ": origin " + m.origin().id() + " must use license '" + SYNTHETIC
                            + "' (no third-party licence applies)");
                }
            }
            case PUBLIC_DATASET -> {
                if (synthetic) {
                    out.add(id + ": origin public-dataset must carry the dataset's own licence, not '" + SYNTHETIC + "'");
                }
            }
            case EMULATOR_CAPTURED -> {
                // emulator output may be ours (synthetic) or carry the emulator's licence; the allowlist decides
            }
            default -> throw new IllegalStateException("unhandled origin " + m.origin());
        }
        boolean mustBeAnonymised = m.origin() == Origin.PUBLIC_DATASET
                || m.origin() == Origin.OWN_ACCOUNT_REDACTED
                || m.origin() == Origin.PARTNER_REDACTED;
        if (mustBeAnonymised && !Boolean.TRUE.equals(m.anonymised())) {
            out.add(id + ": origin " + m.origin().id() + " requires anonymised: true");
        }
    }

    private static void verifyCitation(String id, Meta m, List<String> out) {
        boolean urlPair = m.sourceUrl() != null && m.docDate() != null;
        boolean repoPair = m.repo() != null && m.commit() != null;
        switch (m.origin()) {
            case SYNTHETIC_FROM_DOC, SYNTHETIC_FROM_PROTO -> {
                if (!urlPair) {
                    out.add(id + ": origin " + m.origin().id() + " needs source_url and doc_date");
                }
            }
            case PUBLIC_DATASET -> {
                if (!repoPair) {
                    out.add(id + ": origin public-dataset needs repo and commit");
                }
            }
            case EMULATOR_CAPTURED -> {
                if (!urlPair && !repoPair) {
                    out.add(id + ": origin emulator-captured needs source_url and doc_date, or repo and commit");
                }
            }
            case OWN_ACCOUNT_REDACTED, PARTNER_REDACTED -> {
                if (m.docDate() == null) {
                    out.add(id + ": origin " + m.origin().id() + " needs doc_date (the capture date)");
                }
            }
            default -> throw new IllegalStateException("unhandled origin " + m.origin());
        }
        if (m.sourceUrl() != null && m.docDate() == null) {
            out.add(id + ": source_url needs doc_date");
        }
        if ((m.repo() == null) != (m.commit() == null)) {
            out.add(id + ": repo and commit go together");
        }
        if (m.sourceUrl() != null && !m.sourceUrl().startsWith("https://")) {
            out.add(id + ": source_url must be an https:// URL");
        }
        if (m.repo() != null && !REPO.matcher(m.repo()).matches()) {
            out.add(id + ": repo must be owner/name, got '" + m.repo() + "'");
        }
        if (m.commit() != null && !COMMIT.matcher(m.commit()).matches()) {
            out.add(id + ": commit must be the full 40-character lowercase SHA (quote it in YAML)");
        }
    }

    private static void verifyEvidence(String id, Meta m, List<String> out) {
        if (m.origin() == Origin.EMULATOR_CAPTURED && !m.confirmedBy().isEmpty()) {
            out.add(id + ": an emulator-captured case cannot be independently confirmed (its shape is the "
                    + "emulator's); drop confirmed_by or re-capture from a real account");
        }
        if (m.generator() != null && (m.origin() == Origin.PUBLIC_DATASET
                || m.origin() == Origin.OWN_ACCOUNT_REDACTED || m.origin() == Origin.PARTNER_REDACTED)) {
            out.add(id + ": origin " + m.origin().id() + " is a dataset or a capture, not generator output; "
                    + "remove 'generator'");
        }
        Set<ConfirmationMethod> seen = new LinkedHashSet<>();
        for (Confirmation c : m.confirmedBy()) {
            if (!seen.add(c.method())) {
                out.add(id + ": confirmed_by repeats method " + c.method().id());
            }
            switch (c.method()) {
                case OWN_ACCOUNT_CAPTURE -> {
                    if (m.origin() != Origin.OWN_ACCOUNT_REDACTED) {
                        out.add(id + ": confirmed_by own-account-capture needs origin own-account-redacted");
                    }
                }
                case PARTNER_CAPTURE -> {
                    if (m.origin() != Origin.PARTNER_REDACTED) {
                        out.add(id + ": confirmed_by partner-capture needs origin partner-redacted");
                    }
                }
                case PROTO_ROUND_TRIP, PROCESSING_LIBRARY -> {
                    if (!TEST_EVIDENCE.matcher(c.evidence()).matches()) {
                        out.add(id + ": confirmed_by " + c.method().id() + " evidence must name the test class that "
                                + "runs the check (for example com.dlap.logingest.parse.gcpaudit.ProtoRoundTripTest), got '"
                                + c.evidence() + "'");
                    }
                }
                case OTEL_EXPECTED -> {
                    if (!REPO_EVIDENCE.matcher(c.evidence()).matches()) {
                        out.add(id + ": confirmed_by otel-expected evidence must be owner/name@<40-hex commit>:<path> "
                                + "of the expected-output file, got '" + c.evidence() + "'");
                    }
                }
                default -> throw new IllegalStateException("unhandled confirmation method " + c.method());
            }
        }
    }

    private static void verifyExpected(FixtureCase c, List<String> out) {
        Path expected = c.expectedFile();
        if (!Files.isRegularFile(expected)) {
            out.add(c.id() + ": no " + FixtureCase.EXPECTED_FILE);
            return;
        }
        try {
            JSON.readTree(Files.readString(expected, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            out.add(c.id() + ": " + FixtureCase.EXPECTED_FILE + " is not valid JSON: "
                    + e.getOriginalMessage());
        } catch (IOException e) {
            out.add(c.id() + ": cannot read " + FixtureCase.EXPECTED_FILE + ": " + e.getMessage());
        }
    }

    private static void verifyNotice(FixtureCatalog catalog, String family, List<String> out) {
        Path notice = catalog.root().resolve(family).resolve(FixtureCatalog.NOTICE_FILE);
        String where = family + "/" + FixtureCatalog.NOTICE_FILE;
        boolean specOnly = catalog.spec(family).isPresent()
                && catalog.family(family).isEmpty()
                && catalog.cases(family).isEmpty();
        if (specOnly && !Files.exists(notice)) {
            // Nothing to attribute yet: the family holds only its coverage spec.
            return;
        }
        String text;
        try {
            if (!Files.isRegularFile(notice)) {
                out.add(where + ": missing; every family needs a NOTICE.md (say 'all cases synthetic' if none is third-party)");
                return;
            }
            text = Files.readString(notice, StandardCharsets.UTF_8);
        } catch (IOException e) {
            out.add(where + ": cannot read: " + e.getMessage());
            return;
        }
        if (text.isBlank()) {
            out.add(where + ": is empty");
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> attributed = new LinkedHashSet<>();
        for (FixtureCase c : catalog.cases(family)) {
            Meta m = c.meta();
            if (SYNTHETIC.equals(m.license()) || m.repo() == null) {
                continue;
            }
            if (!attributed.add(m.repo() + "|" + m.license())) {
                continue;
            }
            if (!lower.contains(m.repo().toLowerCase(Locale.ROOT)) || !lower.contains(m.license().toLowerCase(Locale.ROOT))) {
                out.add(where + ": no attribution for " + m.repo() + " (" + m.license() + "); name the repository and the licence");
            }
        }
    }

    /**
     * Command line entry point used by the Gradle task. Arguments: {@code --root <dir>} and an
     * optional {@code --report <file>} that also receives the output.
     *
     * @param args the arguments
     * @throws IOException when the report file cannot be written
     */
    public static void main(String[] args) throws IOException {
        Path root = null;
        Path report = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                default -> {
                    System.err.println("usage: ProvenanceVerifier --root <corpus dir> [--report <file>]");
                    System.exit(2);
                }
            }
        }
        if (root == null) {
            System.err.println("usage: ProvenanceVerifier --root <corpus dir> [--report <file>]");
            System.exit(2);
        }
        Result result = verify(root);
        StringBuilder text = new StringBuilder();
        text.append("fixture provenance: ").append(result.cases()).append(" case(s) in ")
                .append(result.families()).append(" family(ies), ")
                .append(result.violations().size()).append(" violation(s)\n");
        for (String v : result.violations()) {
            text.append("  ").append(v).append('\n');
        }
        System.out.print(text);
        if (report != null) {
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, text.toString(), StandardCharsets.UTF_8);
        }
        if (!result.ok()) {
            System.exit(1);
        }
    }
}
