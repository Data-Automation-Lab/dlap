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

import com.dlap.fixtures.CoverageSpec.Cell;
import com.dlap.fixtures.CoverageSpec.Entry;
import com.dlap.fixtures.FixtureCase.ConfirmationMethod;
import com.dlap.fixtures.FixtureCase.Evidence;
import com.dlap.fixtures.FixtureCatalog.Envelope;
import com.dlap.fixtures.FixtureCatalog.Family;
import com.dlap.fixtures.FixtureCatalog.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Coverage score of the fixture corpus (Gradle task {@code fixtureCoverage}).
 *
 * <p><b>Denominator.</b> When a family has a {@code coverage-spec.yaml} ({@link CoverageSpec}), every
 * scored (envelope, version) cell of the spec is one row, counted against the spec's documented
 * operations and variants. The spec is written from vendor documentation by a package that does not
 * build the parser, so the parser author cannot shrink the denominator. The family's
 * {@code catalog.yaml} only says which cells its parser claims ({@code supported}). A catalog cell the
 * spec does not have, and every cell of a family without a spec, is counted against the catalog
 * itself and is marked so.
 *
 * <p><b>Numerator.</b> Each case has an {@link Evidence} class:
 * <ul>
 *   <li>operations are covered by positive cases that are not emulator captures;</li>
 *   <li>variants are covered by confirmed or authored cases only: emulator-captured and generator-only
 *       cases never count; a variant the spec marks {@code negative} needs a negative case;</li>
 *   <li>confirmed coverage, the same counts using only independently confirmed cases (proto
 *       round-trip, processing library, OTel expected output, own-account or partner capture), is
 *       reported separately and is not part of the score.</li>
 * </ul>
 *
 * <pre>
 *   score = operations covered / documented x variants covered / documented x drift kill ratio
 * </pre>
 * The drift kill ratio comes from the drift mutator (FIX-03); until then it is {@code n/a} and counts as 1.
 *
 * <p><b>Gate.</b> Only rows a {@code catalog.yaml} marks {@code supported: true} are gated. Such a row
 * fails when its score is below the minimum, when it documents no operations, or when it is counted
 * against the parser's own catalog instead of an independent spec (except the tooling self-test family).
 * Claims of operations or variants the denominator does not list are reported as undeclared and never
 * raise the score.
 */
public final class CoverageReport {

    /** The default gate: the plan requires at least 0.99 for every supported (family, envelope, version). */
    public static final double DEFAULT_MIN_SCORE = 0.99;

    private static final double EPSILON = 1e-9;

    private final List<Row> rows;
    private final List<ConfirmedCase> confirmedCases;
    private final List<NotScored> notScored;

    /**
     * Creates a report from computed rows. Package-private so tests can gate hand-built rows; use
     * {@link #compute(FixtureCatalog)} everywhere else.
     *
     * @param rows           the scored rows
     * @param confirmedCases the independently confirmed cases
     * @param notScored      the listed but unscored spec entries
     */
    CoverageReport(List<Row> rows, List<ConfirmedCase> confirmedCases, List<NotScored> notScored) {
        this.rows = rows;
        this.confirmedCases = confirmedCases;
        this.notScored = notScored;
    }

    /** Where a row's denominator comes from. */
    public enum Denominator {
        /** The family's independent {@code coverage-spec.yaml}. */
        SPEC,
        /** The parser package's own {@code catalog.yaml}: self-graded, gated only for the tooling self-test. */
        CATALOG
    }

    /**
     * Coverage of one list of documented ids.
     *
     * @param documented how many ids the denominator lists
     * @param covered    how many of them an eligible case covers
     * @param ratio      covered / documented, 1 when nothing is documented
     * @param missing    documented ids no eligible case covers
     */
    public record Coverage(int documented, int covered, double ratio, List<String> missing) {
    }

    /**
     * The score of one (family, envelope, version).
     *
     * @param family                 the family
     * @param envelope               the envelope
     * @param version                the version
     * @param denominator            where the documented lists come from
     * @param claimed                whether the family's catalog.yaml declares this envelope and version
     * @param supported              whether the catalog marks it supported (gated when true)
     * @param selftest               whether the row is the tooling family {@link FixtureCatalog#SELFTEST_FAMILY}
     * @param operations             operation coverage that counts toward the score
     * @param variants               variant coverage that counts toward the score
     * @param confirmedOperations    operation coverage by independently confirmed cases only
     * @param confirmedVariants      variant coverage by independently confirmed cases only
     * @param driftKillRatio         mutations killed / generated, or null until FIX-03 measures it
     * @param score                  the product of the ratios
     * @param cases                  case count per evidence class in this row
     * @param undeclaredOperations   operations cases claim that the denominator does not list
     * @param undeclaredVariants     variants cases claim that the denominator does not list
     * @param excludedOnlyOperations documented operations claimed only by emulator-captured cases
     * @param excludedOnlyVariants   documented variants claimed only by emulator-captured or generator-only cases
     */
    public record Row(
            String family,
            String envelope,
            String version,
            Denominator denominator,
            boolean claimed,
            boolean supported,
            boolean selftest,
            Coverage operations,
            Coverage variants,
            Coverage confirmedOperations,
            Coverage confirmedVariants,
            Double driftKillRatio,
            double score,
            Map<Evidence, Integer> cases,
            List<String> undeclaredOperations,
            List<String> undeclaredVariants,
            List<String> excludedOnlyOperations,
            List<String> excludedOnlyVariants) {

        /**
         * Returns how many operations the denominator lists.
         *
         * @return the documented operation count
         */
        public int operationsDocumented() {
            return operations.documented();
        }

        /**
         * Returns how many documented operations are covered.
         *
         * @return the covered operation count
         */
        public int operationsCovered() {
            return operations.covered();
        }

        /**
         * Returns how many variants the denominator lists.
         *
         * @return the documented variant count
         */
        public int variantsDocumented() {
            return variants.documented();
        }

        /**
         * Returns how many documented variants are covered.
         *
         * @return the covered variant count
         */
        public int variantsCovered() {
            return variants.covered();
        }

        /**
         * Returns operations covered / documented.
         *
         * @return the operation ratio
         */
        public double operationsRatio() {
            return operations.ratio();
        }

        /**
         * Returns variants covered / documented.
         *
         * @return the variant ratio
         */
        public double variantsRatio() {
            return variants.ratio();
        }

        /**
         * Returns the documented operations without an eligible positive case.
         *
         * @return the missing operations
         */
        public List<String> missingOperations() {
            return operations.missing();
        }

        /**
         * Returns the documented variants without an eligible case.
         *
         * @return the missing variants
         */
        public List<String> missingVariants() {
            return variants.missing();
        }

        /**
         * Returns how many cases of this row have the given evidence class.
         *
         * @param evidence the class
         * @return the count
         */
        public int caseCount(Evidence evidence) {
            return cases.getOrDefault(evidence, 0);
        }

        /**
         * Returns how many cases this row has.
         *
         * @return the total
         */
        public int caseTotal() {
            return cases.values().stream().mapToInt(Integer::intValue).sum();
        }

        /**
         * Returns the row key used in messages.
         *
         * @return {@code family/envelope/version}
         */
        public String key() {
            return family + "/" + envelope + "/" + version;
        }
    }

    /**
     * An independently confirmed case, listed apart from the generated and authored ones.
     *
     * @param id       the case id
     * @param methods  how it was confirmed
     * @param evidence where each confirmation can be re-checked (the capture date for captures)
     */
    public record ConfirmedCase(String id, List<String> methods, List<String> evidence) {
    }

    /**
     * A spec entry that is listed but not scored.
     *
     * @param family the family
     * @param kind   envelope, operation or variant
     * @param id     the entry id
     * @param status unverified or deferred
     * @param reason why it is not scored
     */
    public record NotScored(String family, String kind, String id, String status, String reason) {
    }

    // ------------------------------------------------------------------
    // Computation
    // ------------------------------------------------------------------

    /**
     * Computes the score of every spec cell and every catalog-declared (family, envelope, version).
     *
     * @param catalog the corpus
     * @return the report
     */
    public static CoverageReport compute(FixtureCatalog catalog) {
        List<Row> rows = new ArrayList<>();
        List<NotScored> notScored = new ArrayList<>();
        for (String name : catalog.familyNames()) {
            Optional<Family> family = catalog.family(name);
            Optional<CoverageSpec> spec = catalog.spec(name);
            if (family.isEmpty() && spec.isEmpty()) {
                continue;
            }
            boolean selftest = FixtureCatalog.isToolingFamily(name);
            List<FixtureCase> familyCases = catalog.cases(name);
            Set<String> done = new HashSet<>();
            if (spec.isPresent()) {
                for (Cell cell : spec.get().cells()) {
                    Optional<Version> claim = family.flatMap(f -> f.version(cell.envelope(), cell.version()));
                    List<Entry> ops = spec.get().operations(cell.envelope(), cell.version());
                    List<Entry> vars = spec.get().variants(cell.envelope(), cell.version());
                    rows.add(row(name, cell.envelope(), cell.version(), Denominator.SPEC, claim, selftest,
                            scoredIds(ops), scoredIds(vars), negativeIds(vars), allIds(ops), allIds(vars),
                            familyCases));
                    done.add(cell.envelope() + "\n" + cell.version());
                }
                notScored.addAll(notScored(spec.get()));
            }
            if (family.isPresent()) {
                for (Envelope envelope : family.get().envelopes()) {
                    for (Version version : envelope.versions()) {
                        if (done.contains(envelope.id() + "\n" + version.id())) {
                            continue;
                        }
                        rows.add(row(name, envelope.id(), version.id(), Denominator.CATALOG, Optional.of(version),
                                selftest, version.operations(), version.variants(), List.of(),
                                version.operations(), version.variants(), familyCases));
                    }
                }
            }
        }
        List<ConfirmedCase> confirmed = new ArrayList<>();
        for (FixtureCase c : catalog.cases()) {
            if (c.evidence() == Evidence.CONFIRMED) {
                confirmed.add(confirmedCase(c));
            }
        }
        return new CoverageReport(List.copyOf(rows), List.copyOf(confirmed), List.copyOf(notScored));
    }

    private static Row row(
            String family,
            String envelope,
            String version,
            Denominator denominator,
            Optional<Version> claim,
            boolean selftest,
            List<String> documentedOps,
            List<String> documentedVariants,
            List<String> negativeOnly,
            List<String> declaredOps,
            List<String> declaredVariants,
            List<FixtureCase> familyCases) {
        List<FixtureCase> cases = familyCases.stream()
                .filter(c -> c.envelope().equals(envelope) && c.version().equals(version))
                .toList();
        Map<Evidence, Integer> counts = new EnumMap<>(Evidence.class);
        for (Evidence e : Evidence.values()) {
            counts.put(e, 0);
        }
        cases.forEach(c -> counts.merge(c.evidence(), 1, Integer::sum));

        Set<String> negatives = new TreeSet<>(negativeOnly);
        Predicate<FixtureCase> opEligible = c -> c.evidence() != Evidence.EMULATOR;
        Predicate<FixtureCase> variantEligible = c -> c.evidence() == Evidence.CONFIRMED || c.evidence() == Evidence.AUTHORED;
        Predicate<FixtureCase> confirmed = c -> c.evidence() == Evidence.CONFIRMED;

        Set<String> opsAll = coveredOperations(cases, c -> true);
        Set<String> opsCounted = coveredOperations(cases, opEligible);
        Set<String> opsConfirmed = coveredOperations(cases, confirmed);
        Set<String> variantsAll = coveredVariants(cases, c -> true, negatives);
        Set<String> variantsCounted = coveredVariants(cases, variantEligible, negatives);
        Set<String> variantsConfirmed = coveredVariants(cases, confirmed, negatives);

        Coverage ops = coverage(documentedOps, opsCounted);
        Coverage variants = coverage(documentedVariants, variantsCounted);
        double score = ops.ratio() * variants.ratio();
        Set<String> claimedOps = new TreeSet<>();
        Set<String> claimedVariants = new TreeSet<>();
        for (FixtureCase c : cases) {
            claimedOps.addAll(c.meta().covers());
            claimedVariants.addAll(c.meta().variantIds());
        }
        return new Row(
                family, envelope, version, denominator, claim.isPresent(),
                claim.map(Version::supported).orElse(false), selftest,
                ops, variants,
                coverage(documentedOps, opsConfirmed), coverage(documentedVariants, variantsConfirmed),
                null, score, Collections.unmodifiableMap(counts),
                minus(claimedOps, new TreeSet<>(declaredOps)),
                minus(claimedVariants, new TreeSet<>(declaredVariants)),
                excludedOnly(documentedOps, opsAll, opsCounted),
                excludedOnly(documentedVariants, variantsAll, variantsCounted));
    }

    private static Set<String> coveredOperations(List<FixtureCase> cases, Predicate<FixtureCase> eligible) {
        Set<String> out = new TreeSet<>();
        for (FixtureCase c : cases) {
            if (!c.negative() && eligible.test(c)) {
                out.addAll(c.meta().covers());
            }
        }
        return out;
    }

    private static Set<String> coveredVariants(List<FixtureCase> cases, Predicate<FixtureCase> eligible, Set<String> negativeOnly) {
        Set<String> out = new TreeSet<>();
        for (FixtureCase c : cases) {
            if (!eligible.test(c)) {
                continue;
            }
            for (String v : c.meta().variantIds()) {
                if (c.negative() || !negativeOnly.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static Coverage coverage(List<String> documented, Set<String> covered) {
        List<String> missing = new ArrayList<>();
        Set<String> unique = new TreeSet<>(documented);
        for (String id : documented) {
            if (!covered.contains(id) && !missing.contains(id)) {
                missing.add(id);
            }
        }
        int coveredCount = unique.size() - missing.size();
        return new Coverage(unique.size(), coveredCount, ratio(coveredCount, unique.size()), List.copyOf(missing));
    }

    private static List<String> excludedOnly(List<String> documented, Set<String> claimedByAny, Set<String> counted) {
        List<String> out = new ArrayList<>();
        for (String id : new TreeSet<>(documented)) {
            if (claimedByAny.contains(id) && !counted.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> scoredIds(List<Entry> entries) {
        return entries.stream().filter(Entry::scored).map(Entry::id).toList();
    }

    private static List<String> negativeIds(List<Entry> entries) {
        return entries.stream().filter(e -> e.scored() && e.negative()).map(Entry::id).toList();
    }

    private static List<String> allIds(List<Entry> entries) {
        return entries.stream().map(Entry::id).toList();
    }

    private static List<NotScored> notScored(CoverageSpec spec) {
        List<NotScored> out = new ArrayList<>();
        for (CoverageSpec.Envelope e : spec.envelopes()) {
            if (e.status() != CoverageSpec.Status.DOCUMENTED) {
                out.add(new NotScored(spec.family(), "envelope", e.id(), e.status().id(), e.reason()));
            }
        }
        for (Entry o : spec.operations()) {
            if (!o.scored()) {
                out.add(new NotScored(spec.family(), "operation", o.id(), o.status().id(), o.reason()));
            }
        }
        for (Entry v : spec.variants()) {
            if (!v.scored()) {
                out.add(new NotScored(spec.family(), "variant", v.id(), v.status().id(), v.reason()));
            }
        }
        return out;
    }

    private static ConfirmedCase confirmedCase(FixtureCase c) {
        List<String> methods = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (ConfirmationMethod m : c.confirmationMethods()) {
            methods.add(m.id());
            String where = c.meta().confirmedBy().stream()
                    .filter(x -> x.method() == m)
                    .map(FixtureCase.Confirmation::evidence)
                    .findFirst()
                    .orElse("captured " + c.meta().docDate());
            evidence.add(where);
        }
        return new ConfirmedCase(c.id(), List.copyOf(methods), List.copyOf(evidence));
    }

    private static List<String> minus(Set<String> from, Set<String> remove) {
        List<String> out = new ArrayList<>();
        for (String s : from) {
            if (!remove.contains(s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static double ratio(int covered, int documented) {
        return documented == 0 ? 1.0 : (double) covered / documented;
    }

    // ------------------------------------------------------------------
    // Accessors and gate
    // ------------------------------------------------------------------

    /**
     * Returns the rows: per family in name order, the spec cells in spec order, then the catalog
     * cells the spec does not have, in catalog order.
     *
     * @return the rows
     */
    public List<Row> rows() {
        return rows;
    }

    /**
     * Returns every independently confirmed case of the corpus.
     *
     * @return the confirmed cases, by case id
     */
    public List<ConfirmedCase> confirmedCases() {
        return confirmedCases;
    }

    /**
     * Returns the spec entries that are listed but not scored (unverified or deferred).
     *
     * @return the entries, by family and file order
     */
    public List<NotScored> notScored() {
        return notScored;
    }

    /**
     * Lists why the gate fails. Only supported rows are gated. A supported row fails when it
     * documents no operations, when its score is below the minimum, or when its denominator is the
     * parser package's own catalog. The one exception to the last rule is the tooling family
     * {@link FixtureCatalog#SELFTEST_FAMILY}, matched by name: a {@code selftest} flag in a
     * {@code catalog.yaml}, which parser packages write, never exempts a row.
     *
     * @param minScore the minimum score, normally {@link #DEFAULT_MIN_SCORE}
     * @return one line per failure; empty when the gate passes
     */
    public List<String> gateFailures(double minScore) {
        List<String> failures = new ArrayList<>();
        for (Row r : rows) {
            if (!r.supported()) {
                continue;
            }
            if (r.denominator() == Denominator.CATALOG && !FixtureCatalog.isToolingFamily(r.family())) {
                failures.add(r.key() + ": supported, but counted against the parser's own catalog.yaml; "
                        + "an independent " + r.family() + "/" + CoverageSpec.FILE
                        + " must list this envelope and version before it can be claimed");
            }
            if (r.operationsDocumented() == 0) {
                failures.add(r.key() + ": supported but documents no operations");
            }
            if (r.score() + EPSILON < minScore) {
                failures.add(r.key() + ": score " + format(r.score()) + " is below " + format(minScore)
                        + " (missing operations " + r.missingOperations() + ", missing variants "
                        + r.missingVariants() + ")");
            }
        }
        return failures;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Renders the report as Markdown, the {@code COVERAGE.md} of the corpus.
     *
     * @param minScore the gate shown in the header
     * @return the Markdown text
     */
    public String toMarkdown(double minScore) {
        StringBuilder md = new StringBuilder();
        md.append("# Fixture coverage\n\n");
        md.append("Generated by `:da-log-fixtures:fixtureCoverage` from the `meta.yaml` files, the per-family ")
                .append("`coverage-spec.yaml` and the per-family `catalog.yaml`. Do not edit.\n\n");
        md.append("- **Denominator.** `spec` rows count against the family's independent `coverage-spec.yaml`, written ")
                .append("from vendor documentation by a package that does not build the parser. `catalog` rows count ")
                .append("against the parser package's own `catalog.yaml` (self-graded) and can be gated only for the ")
                .append("tooling self-test family.\n");
        md.append("- **Operations** count positive cases that are not emulator captures.\n");
        md.append("- **Variants** count confirmed and authored cases only. Emulator-captured and generator-only cases ")
                .append("do not count; a variant the spec marks negative needs a negative case.\n");
        md.append("- **Confirmed** columns repeat the counts using only independently confirmed cases (proto round-trip, ")
                .append("processing library, OTel expected output, own-account or partner capture). They are shown ")
                .append("separately and are not part of the score.\n");
        md.append("- **Cases** C/A/G/E: confirmed / authored (hand-written or public dataset) / generator-only / ")
                .append("emulator-captured.\n");
        md.append("- **Score** = operations ratio x variants ratio x drift kill ratio (the drift mutator is FIX-03; ")
                .append("until then `n/a`, counted as 1). Only rows a `catalog.yaml` marks `supported: true` are gated, ")
                .append("at a minimum of ").append(format(minScore)).append(". Supported shows `-` when no catalog claims ")
                .append("the row.\n\n");
        md.append("| Family | Envelope | Version | Denominator | Supported | Operations | Variants | Confirmed ops | ")
                .append("Confirmed variants | Cases C/A/G/E | Drift kill | Score |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.family()).append(r.selftest() ? " [tooling only]" : "")
                    .append(" | ").append(r.envelope())
                    .append(" | ").append(r.version())
                    .append(" | ").append(r.denominator().name().toLowerCase(Locale.ROOT))
                    .append(" | ").append(!r.claimed() ? "-" : r.supported() ? "yes" : "no")
                    .append(" | ").append(fraction(r.operations()))
                    .append(" | ").append(fraction(r.variants()))
                    .append(" | ").append(fraction(r.confirmedOperations()))
                    .append(" | ").append(fraction(r.confirmedVariants()))
                    .append(" | ").append(r.caseCount(Evidence.CONFIRMED)).append('/')
                    .append(r.caseCount(Evidence.AUTHORED)).append('/')
                    .append(r.caseCount(Evidence.GENERATED)).append('/')
                    .append(r.caseCount(Evidence.EMULATOR))
                    .append(" | ").append(r.driftKillRatio() == null ? "n/a" : format(r.driftKillRatio()))
                    .append(" | ").append(format(r.score()))
                    .append(" |\n");
        }

        md.append("\n## Independently confirmed cases\n\n");
        if (confirmedCases.isEmpty()) {
            md.append("None yet. Cases become confirmed through an own-account or partner capture, or a ")
                    .append("`confirmed_by` entry (proto round-trip, processing library, OTel expected output) ")
                    .append("that names re-checkable evidence.\n");
        } else {
            md.append("| Case | Confirmed by | Evidence |\n|---|---|---|\n");
            for (ConfirmedCase c : confirmedCases) {
                md.append("| ").append(c.id()).append(" | ").append(String.join(", ", c.methods()))
                        .append(" | ").append(String.join("; ", c.evidence())).append(" |\n");
            }
        }

        if (!notScored.isEmpty()) {
            md.append("\n## Listed in a spec but not scored\n\n");
            md.append("Unverified entries wait for an opened page or a capture; deferred ones for a recorded decision. ")
                    .append("A spec change that scores them is a spec-only commit.\n\n");
            md.append("| Family | Kind | Id | Status | Reason |\n|---|---|---|---|---|\n");
            for (NotScored n : notScored) {
                md.append("| ").append(n.family()).append(" | ").append(n.kind()).append(" | ").append(n.id())
                        .append(" | ").append(n.status()).append(" | ").append(n.reason().replace("|", "/"))
                        .append(" |\n");
            }
        }

        boolean gaps = false;
        for (Row r : rows) {
            boolean empty = r.caseTotal() == 0;
            boolean complete = r.missingOperations().isEmpty() && r.missingVariants().isEmpty()
                    && r.undeclaredOperations().isEmpty() && r.undeclaredVariants().isEmpty()
                    && r.excludedOnlyOperations().isEmpty() && r.excludedOnlyVariants().isEmpty();
            if (complete) {
                continue;
            }
            if (!gaps) {
                md.append("\n## Gaps\n");
                gaps = true;
            }
            md.append("\n### ").append(r.key()).append("\n\n");
            if (empty) {
                md.append("- No cases yet: ").append(r.operationsDocumented()).append(" documented operations and ")
                        .append(r.variantsDocumented()).append(" documented variants are open.\n");
                continue;
            }
            String source = r.denominator() == Denominator.SPEC ? CoverageSpec.FILE : FixtureCatalog.CATALOG_FILE;
            appendList(md, "Documented operations without a counted positive case", r.missingOperations());
            appendList(md, "Documented variants without a counted case", r.missingVariants());
            appendList(md, "Operations only emulator-captured cases claim (not counted)", r.excludedOnlyOperations());
            appendList(md, "Variants only emulator-captured or generator-only cases claim (not counted)",
                    r.excludedOnlyVariants());
            appendList(md, "Operations cases claim but " + source + " does not list", r.undeclaredOperations());
            appendList(md, "Variants cases claim but " + source + " does not list", r.undeclaredVariants());
        }
        return md.toString();
    }

    private static String fraction(Coverage c) {
        return c.covered() + "/" + c.documented();
    }

    private static void appendList(StringBuilder md, String title, List<String> items) {
        if (!items.isEmpty()) {
            md.append("- ").append(title).append(": ").append(String.join(", ", items)).append('\n');
        }
    }

    /**
     * Renders the report as JSON for tools.
     *
     * @param minScore the gate to record
     * @return pretty-printed JSON
     */
    public String toJson(double minScore) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("minScore", minScore);
        doc.put("rows", rows);
        doc.put("confirmedCases", confirmedCases);
        doc.put("notScored", notScored);
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(doc) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("cannot render the coverage report", e);
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * Command line entry point used by the Gradle task. Arguments: {@code --root <dir>},
     * {@code --out <dir>} (receives {@code COVERAGE.md} and {@code coverage.json}) and an optional
     * {@code --min-score <0..1>} (default 0.99). Exits 1 on a structural problem in the corpus or a
     * failed gate.
     *
     * @param args the arguments
     * @throws IOException when the report files cannot be written
     */
    public static void main(String[] args) throws IOException {
        Path root = null;
        Path out = null;
        double min = DEFAULT_MIN_SCORE;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--min-score" -> min = Double.parseDouble(args[++i]);
                default -> usage();
            }
        }
        if (root == null || out == null || min < 0 || min > 1) {
            usage();
        }
        FixtureCatalog catalog = FixtureCatalog.scan(root);
        if (!catalog.problems().isEmpty()) {
            System.err.println("fixture coverage: the corpus has problems; run verifyFixtureProvenance:");
            catalog.problems().forEach(p -> System.err.println("  " + p));
            System.exit(1);
        }
        CoverageReport report = compute(catalog);
        Files.createDirectories(out);
        Files.writeString(out.resolve("COVERAGE.md"), report.toMarkdown(min), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("coverage.json"), report.toJson(min), StandardCharsets.UTF_8);
        System.out.print(report.toMarkdown(min));
        List<String> failures = report.gateFailures(min);
        if (!failures.isEmpty()) {
            System.err.println("fixture coverage gate failed:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("usage: CoverageReport --root <corpus dir> --out <report dir> [--min-score 0.99]");
        System.exit(2);
    }
}
