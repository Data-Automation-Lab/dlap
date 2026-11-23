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

package com.dlap.catalog.retention;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * The retention evaluation of ADR-0046 and docs/specs/asset-descriptor.md ("Retention >
 * Evaluation"), as a pure function: no clock, no database. The caller passes the as-of instant.
 *
 * <p>For each rule the evidence bounds the true expiry, {@code lo <= expiry <= hi}, where the
 * expiry is the basis time plus the rule's days (a {@code FIXED_DATE} rule's expiry is its own
 * date). A rule is {@code DUE} when {@code hi <= asOf}, {@code RETAINED} when {@code lo > asOf},
 * otherwise {@code UNKNOWN}, so missing evidence never proves an asset stale or fresh. The asset
 * is {@code DUE} only when every rule is, {@code RETAINED} when any rule is, otherwise
 * {@code UNKNOWN}; an asset under a hold is {@code ON_HOLD} whatever the rules say.
 *
 * <table>
 *   <caption>Bounds of the basis time</caption>
 *   <tr><th>Basis</th><th>no earlier than (lo)</th><th>no later than (hi)</th></tr>
 *   <tr><td>CREATED</td><td>cloudCreatedAt</td><td>cloudCreatedAt, else discoveredAt</td></tr>
 *   <tr><td>LAST_MODIFIED</td><td>lastModifiedAt</td><td>none: no write channel proves it (see below)</td></tr>
 *   <tr><td>LAST_ACCESSED</td><td>the later of lastAccessedAt and cloudCreatedAt</td>
 *       <td>with read coverage since S: the latest of lastAccessedAt, S and the CREATED upper bound;
 *       without coverage: none</td></tr>
 *   <tr><td>FIXED_DATE</td><td>the rule's expiry</td><td>the rule's expiry</td></tr>
 * </table>
 *
 * <p><b>Last-modified has no upper bound.</b> {@code lastModifiedAt} comes from audit-log write
 * events only (CAT-02a), and an unlogged later write cannot be ruled out; the spec bounds it only
 * when the value came from provider state, a channel the catalog does not record yet.
 *
 * <p><b>Read coverage is complete only through a watermark.</b> The upper bound of a last-access
 * rule holds for reads that reached the log store. A read after the binding's low watermark may
 * still be on its way, so a last-access rule is proven expired only when its expiry is at or
 * before {@code min(asOf, validThrough)} (a refinement of the spec table, which does not model
 * lag; it never makes a rule due that the spec would call unknown).
 */
public final class RetentionCalculator {

    private RetentionCalculator() {
    }

    /** Status of one rule. */
    enum RuleStatus {
        DUE, RETAINED, UNKNOWN
    }

    /** One rule with its expiry bounds and status. */
    record Judged(RetentionRule rule, RuleStatus status, Instant loExpiry, Instant hiExpiry, String reason) {
    }

    /**
     * Evaluates an asset.
     *
     * @param asOf     the instant the evaluation is true at (the evaluator's injected clock)
     * @param evidence the asset's evidence columns and hold flags
     * @param rules    every rule that applies to the asset, from all four sources
     * @param coverage the asset's read coverage, or {@code null} when no binding captures its reads
     * @return the status, deciding rule and expiry
     */
    public static RetentionEvaluation evaluate(Instant asOf, RetentionEvidence evidence,
                                               List<RetentionRule> rules, ReadCoverage coverage) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(rules, "rules");
        RetentionEvaluation byRules = byRules(asOf, evidence, rules, coverage);
        if (evidence.providerRuleUnreadable()
                && (byRules.status() == RetentionStatus.DUE || byRules.status() == RetentionStatus.NO_POLICY)) {
            // A provider setting exists that cannot be read, so "every rule is expired" or "no rule"
            // is not proven; fail closed. RETAINED is already safe.
            byRules = new RetentionEvaluation(RetentionStatus.UNKNOWN, RetentionEvaluation.PROVIDER_RULE_UNREADABLE,
                    byRules.decidingRule(), null);
        }
        if (evidence.legalHold() || evidence.heldByLabel()) {
            boolean proven = byRules.status() == RetentionStatus.DUE || byRules.status() == RetentionStatus.RETAINED;
            return new RetentionEvaluation(RetentionStatus.ON_HOLD,
                    evidence.legalHold() ? RetentionEvaluation.HOLD_PLACED : RetentionEvaluation.HOLD_LABEL,
                    byRules.decidingRule(), proven ? byRules.expiresAt() : null);
        }
        return byRules;
    }

    private static RetentionEvaluation byRules(Instant asOf, RetentionEvidence evidence,
                                               List<RetentionRule> rules, ReadCoverage coverage) {
        if (rules.isEmpty()) {
            return new RetentionEvaluation(RetentionStatus.NO_POLICY, RetentionEvaluation.NO_RULE, null, null);
        }
        List<Judged> judged = new ArrayList<>(rules.size());
        for (RetentionRule rule : rules) {
            judged.add(judge(asOf, evidence, rule, coverage));
        }
        List<Judged> retained = judged.stream().filter(j -> j.status() == RuleStatus.RETAINED).toList();
        if (!retained.isEmpty()) {
            Judged decisive = retained.stream()
                    .min(Comparator.comparing(Judged::loExpiry, Comparator.reverseOrder())
                            .thenComparing(j -> j.rule(), RetentionRule.TIE_ORDER))
                    .orElseThrow();
            return new RetentionEvaluation(RetentionStatus.RETAINED, RetentionEvaluation.NOT_EXPIRED,
                    decisive.rule(), decisive.loExpiry());
        }
        if (judged.stream().allMatch(j -> j.status() == RuleStatus.DUE)) {
            Judged decisive = judged.stream()
                    .min(Comparator.comparing(Judged::hiExpiry, Comparator.reverseOrder())
                            .thenComparing(j -> j.rule(), RetentionRule.TIE_ORDER))
                    .orElseThrow();
            return new RetentionEvaluation(RetentionStatus.DUE, RetentionEvaluation.EXPIRY_PASSED,
                    decisive.rule(), decisive.hiExpiry());
        }
        Judged undecided = judged.stream()
                .filter(j -> j.status() == RuleStatus.UNKNOWN)
                .min(Comparator.comparing(j -> j.rule(), RetentionRule.TIE_ORDER))
                .orElseThrow();
        return new RetentionEvaluation(RetentionStatus.UNKNOWN, undecided.reason(), undecided.rule(), null);
    }

    /**
     * Bounds one rule's expiry and judges it.
     *
     * @param asOf     the evaluation instant
     * @param evidence the evidence
     * @param rule     the rule
     * @param coverage the read coverage, or {@code null}
     * @return the rule with its bounds and status
     */
    static Judged judge(Instant asOf, RetentionEvidence evidence, RetentionRule rule, ReadCoverage coverage) {
        Instant loBasis = null;
        Instant hiBasis = null;
        Instant dueFence = asOf;
        switch (rule.basis()) {
            case CREATED -> {
                loBasis = evidence.cloudCreatedAt();
                hiBasis = evidence.cloudCreatedAt() != null ? evidence.cloudCreatedAt() : evidence.discoveredAt();
            }
            case LAST_MODIFIED -> loBasis = evidence.lastModifiedAt();
            case LAST_ACCESSED -> {
                loBasis = latest(evidence.lastAccessedAt(), evidence.cloudCreatedAt());
                if (coverage != null) {
                    Instant createdUpper = evidence.cloudCreatedAt() != null
                            ? evidence.cloudCreatedAt() : evidence.discoveredAt();
                    hiBasis = latest(latest(evidence.lastAccessedAt(), coverage.since()), createdUpper);
                    if (coverage.validThrough().isBefore(dueFence)) {
                        dueFence = coverage.validThrough();
                    }
                }
            }
            case FIXED_DATE -> {
                loBasis = rule.fixedExpiry();
                hiBasis = rule.fixedExpiry();
            }
            default -> throw new IllegalStateException("Unhandled basis " + rule.basis());
        }
        Instant lo = expiry(rule, loBasis);
        Instant hi = expiry(rule, hiBasis);
        boolean due = hi != null && !hi.isAfter(dueFence);
        boolean retained = lo != null && lo.isAfter(asOf);
        if (due && retained) {
            return new Judged(rule, RuleStatus.UNKNOWN, lo, hi, RetentionEvaluation.EXPIRY_UNDETERMINED);
        }
        if (due) {
            return new Judged(rule, RuleStatus.DUE, lo, hi, RetentionEvaluation.EXPIRY_PASSED);
        }
        if (retained) {
            return new Judged(rule, RuleStatus.RETAINED, lo, hi, RetentionEvaluation.NOT_EXPIRED);
        }
        return new Judged(rule, RuleStatus.UNKNOWN, lo, hi, undecidedReason(asOf, evidence, rule, coverage, lo, hi));
    }

    private static String undecidedReason(Instant asOf, RetentionEvidence evidence, RetentionRule rule,
                                          ReadCoverage coverage, Instant lo, Instant hi) {
        return switch (rule.basis()) {
            case LAST_MODIFIED -> evidence.lastModifiedAt() == null
                    ? RetentionEvaluation.BASIS_UNKNOWN : RetentionEvaluation.MODIFIED_TIME_UNBOUNDED;
            case LAST_ACCESSED -> {
                if (coverage == null) {
                    yield RetentionEvaluation.READ_COVERAGE_NOT_CAPTURED;
                }
                if (hi != null && !hi.isAfter(asOf)) {
                    yield RetentionEvaluation.READ_COVERAGE_STALE;
                }
                yield lo == null ? RetentionEvaluation.BASIS_UNKNOWN : RetentionEvaluation.EXPIRY_UNDETERMINED;
            }
            case CREATED, FIXED_DATE -> lo == null
                    ? RetentionEvaluation.BASIS_UNKNOWN : RetentionEvaluation.EXPIRY_UNDETERMINED;
        };
    }

    /**
     * The expiry a basis time gives: the time plus the rule's days, or the rule's own date.
     *
     * @param rule  the rule
     * @param basis the basis time, or {@code null} when unknown
     * @return the expiry, or {@code null} when the basis time is unknown
     */
    static Instant expiry(RetentionRule rule, Instant basis) {
        if (basis == null) {
            return null;
        }
        if (rule.basis() == RetentionBasis.FIXED_DATE) {
            return rule.fixedExpiry();
        }
        try {
            return basis.plus(rule.days(), ChronoUnit.DAYS);
        } catch (DateTimeException | ArithmeticException e) {
            // A period so long that the instant overflows: the rule never expires in practice.
            return Instant.MAX;
        }
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
