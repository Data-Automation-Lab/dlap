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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dlap.policyengine.filter.BoundedRegex;

import lombok.extern.slf4j.Slf4j;

/**
 * Evaluates a parsed {@link PolicyDsl} match block against one asset snapshot (the keys of
 * {@link AssetSnapshots}): a lifecycle event's {@code matchSnapshot}, a catalog activity row in
 * a scheduled sweep, a catalog asset in an on-demand evaluation, or the PDP's resolved resource.
 *
 * <p><b>Three-valued evaluation (POL-01).</b> Every clause is {@link MatchOutcome#TRUE},
 * {@link MatchOutcome#FALSE} or {@link MatchOutcome#UNKNOWN}; the block is their conjunction.
 * A value the snapshot does not carry is unknown, never stale, fresh, zero or empty (ADR-0046).
 * {@link #applies} turns the outcome into a decision: a lifecycle policy fires only on
 * {@code TRUE}; an AccessPolicy with {@code effect: deny} applies on {@code TRUE} or
 * {@code UNKNOWN}, one with {@code effect: allow} only on {@code TRUE}. So unknown data is never
 * acted on and never let through by a deny-if-stale AccessPolicy.
 *
 * <p><b>Malformed clauses (POL-02, Q-441).</b> A clause whose value is malformed, or a clause name
 * the DSL does not define, is a policy defect ({@link #clauseDefect}). It evaluates
 * {@code FALSE} in every policy except a deny AccessPolicy, where it evaluates {@code UNKNOWN}:
 * a deny never fails open because its author mistyped a clause.
 *
 * <p><b>Read coverage.</b> A recorded {@code lastAccessedAt} proves that the asset was read then.
 * It proves that nothing read it since only when read logging covers the asset fully
 * ({@code readCoverage: FULL}). When {@link Options#fullReadCoverageRequired()} is set (destructive
 * and retention-shortening actions and AccessPolicies, see {@link #requiresFullReadCoverage}), a
 * recorded read under {@code PARTIAL}, {@code NONE} or unknown coverage is a lower bound of the true
 * last read: an {@code olderThan} test it passes is {@code UNKNOWN}, and a {@code youngerThan} test
 * it fails is {@code UNKNOWN}; the tests it decides the other way stay decided. Under the same rule
 * a {@code FULL} observed longer ago than {@code dlap.policy.coverage.max-age} (default
 * {@code P2D}, Q-475) counts as unknown coverage; a {@code FULL} without an observation time keeps
 * the v1.2 behaviour and is logged.
 *
 * <p><b>Coverage start (POL-03, Q-434).</b> Under {@code FULL} coverage that counts (fresh under the
 * rule, at face value otherwise) with a known {@code readCoverageSince}, every read since that start
 * is recorded. When no read is recorded at or after the start, the true last read is before the
 * start (or there was none): the start is an exclusive upper bound and a recorded earlier read a
 * lower bound. So {@code olderThan: d} is {@code TRUE} once the start is at least {@code d} old,
 * even for an asset never read, and {@code youngerThan: d} is then {@code FALSE}. Outside the rule a
 * recorded read keeps its face value; the start is used only when no read is recorded.
 *
 * <p><b>Time.</b> Ages are measured against {@link Options#now()}: the injected {@link Clock} for
 * event and on-demand evaluation, the sweep's as-of instant for a scheduled fire. The wall clock
 * is never read directly (plan rule R6).
 *
 * <p>Clauses (spec {@code docs/specs/policy-dsl.md}): {@code cloudProvider}, {@code assetType},
 * {@code region} (string or {@code anyOf}/{@code noneOf}); {@code labels}
 * ({@code anyOf}/{@code noneOf}/{@code allOf}); {@code lastAccessedAt} and {@code createdAt}
 * ({@code olderThan}/{@code youngerThan}, ISO-8601 duration); {@code sizeBytes}
 * ({@code gte}/{@code lte}/{@code eq}); {@code nameMatches} (RE2/J through {@link BoundedRegex},
 * SEC-04); {@code tagMatches}. An empty match block matches every asset.
 */
@Component
@Slf4j
public class PolicyDslEvaluator {

    /** Clause names the evaluator understands. */
    public static final Set<String> CLAUSES = Set.of("cloudProvider", "assetType", "region", "labels",
            "lastAccessedAt", "createdAt", "sizeBytes", "nameMatches", "tagMatches");

    /** Default of {@code dlap.policy.coverage.max-age}. */
    public static final Duration DEFAULT_COVERAGE_MAX_AGE = Duration.ofDays(2);

    private static final Set<String> STRING_OPERATORS = Set.of("anyOf", "noneOf");
    private static final Set<String> LABEL_OPERATORS = Set.of("anyOf", "noneOf", "allOf");
    private static final Set<String> AGE_OPERATORS = Set.of("olderThan", "youngerThan");
    private static final Set<String> SIZE_OPERATORS = Set.of("gte", "lte", "eq");

    private final Clock clock;
    private final Duration coverageMaxAge;

    /**
     * Evaluator on the system UTC clock, for tests and callers outside Spring.
     */
    public PolicyDslEvaluator() {
        this(Clock.systemUTC());
    }

    /**
     * Evaluator on the given clock with the default coverage freshness bound.
     *
     * @param clock the business clock
     */
    public PolicyDslEvaluator(Clock clock) {
        this(clock, DEFAULT_COVERAGE_MAX_AGE);
    }

    /**
     * Evaluator on the given clock.
     *
     * @param clock          the business clock
     * @param coverageMaxAge how old a {@code FULL} read-coverage observation may be before it counts
     *                       as unknown coverage where full coverage is required
     */
    public PolicyDslEvaluator(Clock clock, Duration coverageMaxAge) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.coverageMaxAge = Objects.requireNonNull(coverageMaxAge, "coverageMaxAge");
        if (coverageMaxAge.isNegative()) {
            throw new IllegalArgumentException("dlap.policy.coverage.max-age must not be negative: " + coverageMaxAge);
        }
    }

    /**
     * Spring constructor: {@code dlap.policy.coverage.max-age} is an ISO-8601 duration.
     *
     * @param clock          the business clock
     * @param coverageMaxAge the setting, for example {@code P2D}
     */
    @Autowired
    public PolicyDslEvaluator(Clock clock, @Value("${dlap.policy.coverage.max-age:P2D}") String coverageMaxAge) {
        this(clock, Duration.parse(coverageMaxAge.strip()));
    }

    /**
     * How one evaluation runs.
     *
     * @param now                      the instant ages are measured against
     * @param fullReadCoverageRequired whether a recorded last read counts as proof of no later read
     *                                 only under fresh {@code readCoverage: FULL}
     * @param coverageMaxAge           how old a {@code FULL} observation may be under that rule;
     *                                 {@code null} skips the freshness test
     * @param coverageWarned           asset keys already warned about in this run for a {@code FULL}
     *                                 without an observation time (mutable, one per sweep or event);
     *                                 {@code null} logs such assets at DEBUG instead (the PDP)
     */
    public record Options(Instant now, boolean fullReadCoverageRequired, Duration coverageMaxAge,
            Set<String> coverageWarned) {

        /**
         * Rejects a missing instant.
         */
        public Options {
            Objects.requireNonNull(now, "now");
        }

        /**
         * Options without the coverage freshness test (v1.2 behaviour).
         *
         * @param now                      the instant ages are measured against
         * @param fullReadCoverageRequired the read-coverage rule
         */
        public Options(Instant now, boolean fullReadCoverageRequired) {
            this(now, fullReadCoverageRequired, null, null);
        }
    }

    /**
     * The configured freshness bound of a {@code FULL} read-coverage observation.
     *
     * @return {@code dlap.policy.coverage.max-age}
     */
    public Duration coverageMaxAge() {
        return coverageMaxAge;
    }

    /**
     * The options for one evaluation run of a policy: the read-coverage rule its kind and action
     * require and the configured freshness bound.
     *
     * @param now            the evaluation instant
     * @param policy         the policy; may be {@code null}
     * @param coverageWarned the run's set of assets already warned about; may be {@code null}
     * @return the options
     */
    public Options options(Instant now, PolicyDsl policy, Set<String> coverageWarned) {
        return options(now, requiresFullReadCoverage(policy), coverageWarned);
    }

    /**
     * The options for one evaluation run with an explicit read-coverage rule.
     *
     * @param now                      the evaluation instant
     * @param fullReadCoverageRequired the read-coverage rule
     * @param coverageWarned           the run's set of assets already warned about; may be {@code null}
     * @return the options
     */
    public Options options(Instant now, boolean fullReadCoverageRequired, Set<String> coverageWarned) {
        return new Options(now, fullReadCoverageRequired, coverageMaxAge, coverageWarned);
    }

    /**
     * The options for a policy evaluated now: the clock's instant, the read-coverage rule the
     * policy's kind and action require, and the freshness bound.
     *
     * @param policy the policy; may be {@code null}
     * @return the options
     */
    public Options optionsFor(PolicyDsl policy) {
        return options(clock.instant(), policy, null);
    }

    /**
     * The options for a policy evaluated now, with a set of assets already warned about for a
     * {@code FULL} without an observation time (the PDP passes a bounded set it clears on each policy
     * refresh, so it warns once per asset per refresh, POL-03).
     *
     * @param policy         the policy; may be {@code null}
     * @param coverageWarned the assets already warned about; may be {@code null} (DEBUG only)
     * @return the options
     */
    public Options optionsFor(PolicyDsl policy, Set<String> coverageWarned) {
        return options(clock.instant(), policy, coverageWarned);
    }

    /**
     * Two-valued decision for a policy evaluated now: {@link #evaluate} then {@link #applies}.
     *
     * @param policy        the policy
     * @param assetSnapshot the snapshot; {@code null} is an empty snapshot
     * @return whether the policy applies to the asset
     */
    public boolean matches(PolicyDsl policy, Map<String, Object> assetSnapshot) {
        return applies(policy, evaluate(policy, assetSnapshot, optionsFor(policy)));
    }

    /**
     * Evaluates the policy's match block.
     *
     * @param policy        the policy
     * @param assetSnapshot the snapshot; {@code null} is an empty snapshot
     * @param options       time and read-coverage rule
     * @return the three-valued outcome and the clauses that were unknown
     */
    public MatchResult evaluate(PolicyDsl policy, Map<String, Object> assetSnapshot, Options options) {
        if (policy == null || policy.getSpec() == null) {
            return new MatchResult(MatchOutcome.FALSE, List.of());
        }
        if (policy.getMetadata() != null && !policy.getMetadata().isEnabled()) {
            return new MatchResult(MatchOutcome.FALSE, List.of());
        }
        Map<String, Object> match = policy.getSpec().getMatch();
        if (match == null || match.isEmpty()) {
            return new MatchResult(MatchOutcome.TRUE, List.of());
        }
        MatchOutcome onDefect = isDenyAccessPolicy(policy) ? MatchOutcome.UNKNOWN : MatchOutcome.FALSE;
        Map<String, Object> asset = assetSnapshot != null ? assetSnapshot : Map.of();
        MatchOutcome outcome = MatchOutcome.TRUE;
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Object> clause : match.entrySet()) {
            MatchOutcome c;
            String defect = clauseDefect(clause.getKey(), clause.getValue());
            if (defect != null) {
                // Logged once per policy at start-up (StoredPolicyValidator); here only at DEBUG,
                // because the PDP and the sweep evaluate the same defect for every asset.
                log.debug("Malformed match clause '{}' in policy {}: {}; evaluates {}", clause.getKey(),
                        policyId(policy), defect, onDefect);
                c = onDefect;
            } else {
                c = evaluateClause(clause.getKey(), clause.getValue(), asset, options);
            }
            if (c == MatchOutcome.UNKNOWN) {
                unknown.add(clause.getKey());
            }
            outcome = outcome.and(c);
        }
        return new MatchResult(outcome, outcome == MatchOutcome.UNKNOWN ? unknown : List.of());
    }

    /**
     * Whether a policy applies given its match result: an AccessPolicy with {@code effect: deny}
     * applies unless the match is {@code FALSE}; every other policy only when it is {@code TRUE}.
     *
     * @param policy the policy
     * @param result its match result
     * @return the decision
     */
    public static boolean applies(PolicyDsl policy, MatchResult result) {
        if (result == null) {
            return false;
        }
        if (isDenyAccessPolicy(policy)) {
            return result.outcome() != MatchOutcome.FALSE;
        }
        return result.isTrue();
    }

    /**
     * Whether a policy is an AccessPolicy with {@code effect: deny}.
     *
     * @param policy the policy; may be {@code null}
     * @return the test
     */
    public static boolean isDenyAccessPolicy(PolicyDsl policy) {
        return policy != null && PolicyDsl.KIND_ACCESS_POLICY.equals(policy.getKind())
                && policy.getSpec() != null && PolicyDsl.EFFECT_DENY.equals(policy.getSpec().getEffect());
    }

    /**
     * Whether an action type is destructive: it archives or deletes data (any type naming
     * {@code archive} or {@code delete}, ignoring case, the way the lifecycle consumers match).
     *
     * @param actionType the action type; may be {@code null}
     * @return true for archive and delete actions
     */
    public static boolean isDestructive(String actionType) {
        if (actionType == null) {
            return false;
        }
        String t = actionType.toLowerCase(Locale.ROOT);
        return t.contains("archive") || t.contains("delete");
    }

    /**
     * Whether an action type can shorten an asset's effective retention (Q-472): {@code setRetention},
     * compared the way da-catalog compares it (without case, dots, underscores, hyphens or spaces).
     * The engine does not see the asset's retention rules, and a {@code setRetention} shortens the
     * effective retention whenever the asset had no rule (none means never due) or the policy's own
     * earlier rule was longer, so every {@code setRetention} counts.
     *
     * @param actionType the action type; may be {@code null}
     * @return true for {@code setRetention}
     */
    public static boolean isRetentionShortening(String actionType) {
        if (actionType == null) {
            return false;
        }
        String t = actionType.toLowerCase(Locale.ROOT).replaceAll("[._\\-\\s]", "");
        return "setretention".equals(t);
    }

    /**
     * Whether an action type counts as destructive for the read-coverage rule: it archives, deletes
     * or can shorten retention ({@link #isDestructive}, {@link #isRetentionShortening}). Approval
     * routing is decided elsewhere ({@code ActionClass}); a {@code setRetention} needs no approval.
     *
     * @param actionType the action type; may be {@code null}
     * @return the test
     */
    public static boolean needsFullReadCoverage(String actionType) {
        return isDestructive(actionType) || isRetentionShortening(actionType);
    }

    /**
     * Whether evaluating this policy requires full read coverage before a recorded last read
     * counts as proof of no later read (critique correction 2, Q-472): AccessPolicies and lifecycle
     * policies with a destructive or retention-shortening action.
     *
     * @param policy the policy; may be {@code null}
     * @return the rule
     */
    public static boolean requiresFullReadCoverage(PolicyDsl policy) {
        if (policy == null) {
            return true;
        }
        if (PolicyDsl.KIND_ACCESS_POLICY.equals(policy.getKind())) {
            return true;
        }
        PolicyDsl.Action action = policy.getSpec() != null ? policy.getSpec().getAction() : null;
        return action != null && needsFullReadCoverage(action.getType());
    }

    /* ----- malformed clauses ----- */

    /**
     * The defects of a policy's match block, one line per malformed clause ({@code "clause: why"}),
     * in match order. Empty for a well-formed block. The evaluator applies the same test to every
     * clause it evaluates, so this is exactly the set of clauses that evaluate as defects.
     *
     * @param policy the policy; may be {@code null}
     * @return the defects
     */
    public static List<String> defects(PolicyDsl policy) {
        Map<String, Object> match = policy != null && policy.getSpec() != null ? policy.getSpec().getMatch() : null;
        if (match == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        match.forEach((key, spec) -> {
            String defect = clauseDefect(key, spec);
            if (defect != null) {
                out.add(key + ": " + defect);
            }
        });
        return out;
    }

    /**
     * Why one match clause is malformed, or {@code null} when it is well formed. Malformed: a
     * clause name not in {@link #CLAUSES}; an operator map that is not a map, names no operator or
     * names one the clause does not define; an operator value of the wrong type (a list for
     * {@code anyOf}/{@code noneOf}/{@code allOf}, an ISO-8601 duration for
     * {@code olderThan}/{@code youngerThan}, a whole number for {@code gte}/{@code lte}/{@code eq});
     * a {@code nameMatches} that is not a bounded RE2 pattern; an empty or non-map
     * {@code tagMatches}.
     *
     * @param key  the clause name
     * @param spec the clause value
     * @return the reason, or {@code null}
     */
    static String clauseDefect(String key, Object spec) {
        if (key == null) {
            return "has no name";
        }
        return switch (key) {
            case "cloudProvider", "assetType", "region" -> spec instanceof String ? null
                    : operatorDefect(spec, STRING_OPERATORS, PolicyDslEvaluator::listDefect);
            case "labels" -> operatorDefect(spec, LABEL_OPERATORS, PolicyDslEvaluator::listDefect);
            case "lastAccessedAt", "createdAt" -> operatorDefect(spec, AGE_OPERATORS, PolicyDslEvaluator::durationDefect);
            case "sizeBytes" -> operatorDefect(spec, SIZE_OPERATORS, PolicyDslEvaluator::numberDefect);
            case "nameMatches" -> regexDefect(spec);
            case "tagMatches" -> spec instanceof Map<?, ?> m && !m.isEmpty() ? null
                    : "must be a non-empty map of tag key to value";
            default -> "is not a match clause";
        };
    }

    private static String operatorDefect(Object spec, Set<String> operators, Function<Object, String> valueDefect) {
        String expected = operators.stream().sorted().collect(Collectors.joining(", "));
        if (!(spec instanceof Map<?, ?> m)) {
            return "must be a map naming " + expected;
        }
        if (m.isEmpty()) {
            return "names none of " + expected;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String op = String.valueOf(e.getKey());
            if (!operators.contains(op)) {
                return "unknown operator '" + op + "' (expected " + expected + ")";
            }
            String why = valueDefect.apply(e.getValue());
            if (why != null) {
                return op + " " + why;
            }
        }
        return null;
    }

    private static String listDefect(Object v) {
        return v instanceof Collection<?> ? null : "must be a list";
    }

    private static String durationDefect(Object v) {
        if (!(v instanceof String s)) {
            return "must be an ISO-8601 duration string";
        }
        try {
            Duration.parse(s);
            return null;
        } catch (DateTimeParseException e) {
            return "is not an ISO-8601 duration: " + s;
        }
    }

    private static String numberDefect(Object v) {
        return asLong(v) != null ? null : "must be a whole number";
    }

    private static String regexDefect(Object spec) {
        if (!(spec instanceof String pattern)) {
            return "must be a regular expression string";
        }
        try {
            BoundedRegex.compile(pattern);
            return null;
        } catch (BoundedRegex.InvalidRegexException e) {
            return "is not a bounded RE2 pattern (" + pattern.length() + " chars): " + e.getMessage();
        }
    }

    /* ----- clause evaluators (the clause is well formed here) ----- */

    private MatchOutcome evaluateClause(String key, Object spec, Map<String, Object> asset, Options options) {
        return switch (key) {
            case "cloudProvider" -> evalStringClause(spec, asString(asset.get(AssetSnapshots.CLOUD_PROVIDER)));
            case "assetType" -> evalStringClause(spec, asString(asset.get(AssetSnapshots.ASSET_TYPE)));
            case "region" -> evalStringClause(spec, asString(asset.get(AssetSnapshots.REGION)));
            case "labels" -> evalSetClause((Map<?, ?>) spec, asset.get(AssetSnapshots.LABELS));
            case "lastAccessedAt" -> evalLastReadClause((Map<?, ?>) spec, asset, options);
            case "createdAt" -> evalAgeClause((Map<?, ?>) spec, asInstant(asset.get(AssetSnapshots.CLOUD_CREATED_AT)),
                    options.now(), false);
            case "sizeBytes" -> evalNumericClause((Map<?, ?>) spec, asLong(asset.get(AssetSnapshots.SIZE_BYTES)));
            case "nameMatches" -> {
                Object name = asset.get(AssetSnapshots.ASSET_NAME) != null
                        ? asset.get(AssetSnapshots.ASSET_NAME) : asset.get(AssetSnapshots.NAME);
                yield evalRegexClause((String) spec, asString(name));
            }
            case "tagMatches" -> evalTagClause((Map<?, ?>) spec, asset.get(AssetSnapshots.TAGS));
            default -> MatchOutcome.FALSE;
        };
    }

    /**
     * {@code lastAccessedAt}: the recorded last read, judged with the read-coverage rule and, when the
     * coverage counts as {@code FULL} and its start is known, with the start as the bound of an
     * unrecorded last read (POL-03, Q-434). See the class comment.
     */
    private MatchOutcome evalLastReadClause(Map<?, ?> spec, Map<String, Object> asset, Options options) {
        Instant recorded = asInstant(asset.get(AssetSnapshots.LAST_ACCESSED_AT));
        boolean underRule = options.fullReadCoverageRequired();
        boolean full = underRule ? provesFullCoverage(asset, options) : isFull(asset);
        Instant since = full ? asInstant(asset.get(AssetSnapshots.READ_COVERAGE_SINCE)) : null;
        boolean noReadSinceStart = since != null && (recorded == null || recorded.isBefore(since));
        if (noReadSinceStart && (underRule || recorded == null)) {
            return evalBoundedLastRead(spec, recorded, since, options.now());
        }
        return evalAgeClause(spec, recorded, options.now(), underRule && !full);
    }

    /**
     * An age test on a last read known only to lie before {@code before} (exclusive) and, when
     * {@code atLeast} is set, at or after it. {@code olderThan: d} holds when the last read is before
     * {@code now - d}; {@code youngerThan: d} when it is after.
     */
    private static MatchOutcome evalBoundedLastRead(Map<?, ?> m, Instant atLeast, Instant before, Instant now) {
        Duration olderThan = duration(m.get("olderThan"));
        Duration youngerThan = duration(m.get("youngerThan"));
        MatchOutcome outcome = MatchOutcome.TRUE;
        if (olderThan != null) {
            Instant cutoff = now.minus(olderThan);
            MatchOutcome c = !before.isAfter(cutoff) ? MatchOutcome.TRUE
                    : atLeast != null && !atLeast.isBefore(cutoff) ? MatchOutcome.FALSE : MatchOutcome.UNKNOWN;
            outcome = outcome.and(c);
        }
        if (youngerThan != null) {
            Instant cutoff = now.minus(youngerThan);
            MatchOutcome c = atLeast != null && atLeast.isAfter(cutoff) ? MatchOutcome.TRUE
                    : !before.isAfter(cutoff) ? MatchOutcome.FALSE : MatchOutcome.UNKNOWN;
            outcome = outcome.and(c);
        }
        return outcome;
    }

    /** Whether the snapshot's read coverage is {@code FULL}, at face value (no freshness test). */
    private static boolean isFull(Map<String, Object> asset) {
        return AssetSnapshots.COVERAGE_FULL.equals(AssetSnapshots.normaliseCoverage(asset.get(AssetSnapshots.READ_COVERAGE)));
    }

    /**
     * Whether the snapshot's read coverage proves that every read is recorded, for the full
     * coverage rule: {@code FULL}, observed no longer ago than the freshness bound. A {@code FULL}
     * without an observation time counts (the v1.2 behaviour, until every catalog sends the time)
     * and is warned about once per asset per run; one whose time does not parse does not count.
     */
    private boolean provesFullCoverage(Map<String, Object> asset, Options options) {
        if (!isFull(asset)) {
            return false;
        }
        if (options.coverageMaxAge() == null) {
            return true;
        }
        Object rawObserved = asset.get(AssetSnapshots.READ_COVERAGE_OBSERVED_AT);
        if (rawObserved == null || rawObserved.toString().isBlank()) {
            warnUnstamped(asset, options);
            return true;
        }
        Instant observed = asInstant(rawObserved);
        if (observed == null) {
            log.debug("Asset {}: readCoverageObservedAt '{}' is not an instant; FULL coverage does not count",
                    assetKey(asset), rawObserved);
            return false;
        }
        return !observed.isBefore(options.now().minus(options.coverageMaxAge()));
    }

    private static void warnUnstamped(Map<String, Object> asset, Options options) {
        String key = assetKey(asset);
        if (options.coverageWarned() != null && options.coverageWarned().add(key)) {
            log.warn("Asset {} has readCoverage FULL without readCoverageObservedAt; its freshness cannot be "
                    + "checked against dlap.policy.coverage.max-age, so FULL is taken at face value", key);
        } else if (options.coverageWarned() == null) {
            log.debug("Asset {} has readCoverage FULL without readCoverageObservedAt", key);
        }
    }

    private static String assetKey(Map<String, Object> asset) {
        String key = AssetSnapshots.firstText(asset, AssetSnapshots.ASSET_ID, AssetSnapshots.ASSET_URI);
        return key != null ? key : "(unidentified)";
    }

    private static String policyId(PolicyDsl policy) {
        return policy.getMetadata() != null ? policy.getMetadata().getId() : null;
    }

    private MatchOutcome evalStringClause(Object spec, String actual) {
        if (spec instanceof String s) {
            return actual == null ? MatchOutcome.UNKNOWN : MatchOutcome.of(s.equals(actual));
        }
        if (actual == null) {
            return MatchOutcome.UNKNOWN;
        }
        Map<?, ?> m = (Map<?, ?>) spec;
        boolean holds = true;
        if (m.get("anyOf") instanceof Collection<?> c) {
            holds = c.stream().map(String::valueOf).anyMatch(actual::equals);
        }
        if (m.get("noneOf") instanceof Collection<?> c) {
            holds = holds && c.stream().map(String::valueOf).noneMatch(actual::equals);
        }
        return MatchOutcome.of(holds);
    }

    /**
     * {@code nameMatches}: the spec value is a regex tested against the asset name with
     * {@code find} semantics. It runs on RE2/J with a length cap (SEC-04).
     */
    private MatchOutcome evalRegexClause(String pattern, String actual) {
        return actual == null ? MatchOutcome.UNKNOWN : MatchOutcome.of(BoundedRegex.compile(pattern).find(actual));
    }

    private MatchOutcome evalSetClause(Map<?, ?> m, Object rawActual) {
        if (rawActual == null) {
            return MatchOutcome.UNKNOWN;
        }
        Set<String> actual = asLabelSet(rawActual);
        if (m.get("anyOf") instanceof Collection<?> c
                && c.stream().map(String::valueOf).noneMatch(actual::contains)) {
            return MatchOutcome.FALSE;
        }
        if (m.get("noneOf") instanceof Collection<?> c
                && c.stream().map(String::valueOf).anyMatch(actual::contains)) {
            return MatchOutcome.FALSE;
        }
        if (m.get("allOf") instanceof Collection<?> c
                && !actual.containsAll(c.stream().map(String::valueOf).collect(Collectors.toSet()))) {
            return MatchOutcome.FALSE;
        }
        return MatchOutcome.TRUE;
    }

    /**
     * {@code olderThan} / {@code youngerThan} on an event time. When {@code lowerBound} is set the
     * recorded time may be earlier than the true one (incomplete read logging), so only the
     * results the recorded time decides either way are kept.
     */
    private MatchOutcome evalAgeClause(Map<?, ?> m, Instant actual, Instant now, boolean lowerBound) {
        Duration olderThan = duration(m.get("olderThan"));
        Duration youngerThan = duration(m.get("youngerThan"));
        if (actual == null) {
            return MatchOutcome.UNKNOWN;
        }
        Duration age = Duration.between(actual, now);
        MatchOutcome outcome = MatchOutcome.TRUE;
        if (olderThan != null) {
            boolean holds = age.compareTo(olderThan) > 0;
            outcome = outcome.and(!holds ? MatchOutcome.FALSE : lowerBound ? MatchOutcome.UNKNOWN : MatchOutcome.TRUE);
        }
        if (youngerThan != null) {
            boolean holds = age.compareTo(youngerThan) < 0;
            outcome = outcome.and(holds ? MatchOutcome.TRUE : lowerBound ? MatchOutcome.UNKNOWN : MatchOutcome.FALSE);
        }
        return outcome;
    }

    private MatchOutcome evalNumericClause(Map<?, ?> m, Long actual) {
        if (actual == null) {
            return MatchOutcome.UNKNOWN;
        }
        Long gte = asLong(m.get("gte"));
        Long lte = asLong(m.get("lte"));
        Long eq = asLong(m.get("eq"));
        if (gte != null && actual < gte) {
            return MatchOutcome.FALSE;
        }
        if (lte != null && actual > lte) {
            return MatchOutcome.FALSE;
        }
        if (eq != null && !actual.equals(eq)) {
            return MatchOutcome.FALSE;
        }
        return MatchOutcome.TRUE;
    }

    /**
     * {@code tagMatches}: map equality on cloud-native tags. Every {@code key: value} in the spec
     * must be present on the asset's {@code tags} map. An asset whose known tag map is empty does
     * not match; an asset whose tags are unknown is unknown.
     */
    private MatchOutcome evalTagClause(Map<?, ?> m, Object rawTags) {
        if (!(rawTags instanceof Map<?, ?> tags)) {
            return MatchOutcome.UNKNOWN;
        }
        Map<String, String> actual = asTagMap(tags);
        if (actual.isEmpty()) {
            return MatchOutcome.FALSE;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!Objects.equals(actual.get(asString(e.getKey())), asString(e.getValue()))) {
                return MatchOutcome.FALSE;
            }
        }
        return MatchOutcome.TRUE;
    }

    /* ----- coercion helpers ----- */

    private static Duration duration(Object v) {
        return v == null ? null : Duration.parse(v.toString());
    }

    private static Map<String, String> asTagMap(Map<?, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey().toString(), e.getValue().toString());
            }
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant asInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        try {
            return Instant.parse(v.toString().strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Set<String> asLabelSet(Object v) {
        if (v instanceof Collection<?> c) {
            return c.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toSet());
        }
        if (v instanceof String s) {
            return Set.of(s);
        }
        return Set.of();
    }
}
